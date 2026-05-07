package com.enterprise.dataanalyst.service.llm;

import com.enterprise.dataanalyst.exception.QueryProcessingException;
import com.enterprise.dataanalyst.model.ColumnMetadata;
import com.enterprise.dataanalyst.model.TableMetadata;
import com.enterprise.dataanalyst.service.profiler.ColumnProfileRegistry;
import com.enterprise.dataanalyst.service.query.QueryExecutorService;
import com.enterprise.dataanalyst.service.registry.TableMetadataRegistry;
import com.enterprise.dataanalyst.service.resolver.SmartTableResolver;
import de.kherud.llama.InferenceParameters;
import de.kherud.llama.LlamaModel;
import de.kherud.llama.LlamaOutput;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * SQL generation service — THREE paths:
 *
 * PATH 1 — DETERMINISTIC (fast, ~0ms):
 *   Known intents intercepted before LLM.
 *   Schema queries, count, preview, duplicates,
 *   missing values — all handled deterministically.
 *   Table auto-resolved from registry.
 *
 * PATH 2 — LLM PASS 1 (schema-only, ~5s):
 *   Complex queries. Schema DDL only. No data values.
 *   Works for ~80% of LLM queries.
 *
 * PATH 3 — LLM PASS 2 (data-aware, ~8-10s):
 *   Fires ONLY when Pass 1 fails (column not found, wrong table, etc.)
 *   Enriched prompt with actual data values from profiler.
 *   The LLM dynamically reasons about concepts like "female" (→ customer_segment),
 *   "adult" (→ age >= 18), "senior citizen" (→ age >= 60).
 *   NO hardcoded synonyms — the LLM already knows these concepts.
 *
 * WHY TWO LLM PASSES:
 * Pass 1 is fast and works most of the time. Pass 2 is slower but smarter.
 * We only pay the Pass 2 cost when Pass 1 genuinely fails.
 * The same SQLCoder-7B model handles both — just different prompts.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SQLCoderService {

    private final LlamaModel llamaModel;
    private final SchemaContextBuilder schemaContextBuilder;
    private final SQLSanitizer sqlSanitizer;
    private final TableMetadataRegistry registry;
    private final SmartTableResolver smartTableResolver;
    private final DataAwarePromptBuilder dataAwarePromptBuilder;
    private final ColumnProfileRegistry profileRegistry;

    @Value("${app.llm.temperature:0.1}")
    private float temperature;

    @Value("${app.llm.max-tokens:300}")
    private int maxTokens;

    @Value("${app.llm.pass2.enabled:true}")
    private boolean pass2Enabled;

    // ─── PUBLIC API ───────────────────────────────────────────────

    public String generateSQL(String userQuery) {
        log.info("Processing query: '{}'", userQuery);
        String lower = userQuery.toLowerCase().trim();

        // Step 1: Resolve table name
        String resolvedTable = smartTableResolver.resolve(userQuery);

        // Step 2: Try deterministic path (instant)
        String deterministicSQL = tryDeterministic(lower, resolvedTable);
        if (deterministicSQL != null) return deterministicSQL;

        // Step 3: LLM Reasoning & Generation (Pass 1 & 2)
        String pass1SQL = null;
        String pass1Error = null;

        try {
            // Attempt 1: Standard Generation (Pass 1)
            pass1SQL = generatePass1(userQuery, resolvedTable);
            
            // Hallucination Check
            if (resolvedTable != null && hasColumnMismatch(pass1SQL, resolvedTable)) {
                log.info("Pass 1 Hallucinated columns. Escalating to Pass 2...");
                return generatePass2(userQuery, resolvedTable, pass1SQL, "Reference to non-existent columns detected.");
            }
            return pass1SQL;
        } catch (Exception e) {
            log.info("Pass 1 Failed: {}. Escalating to Pass 2...", e.getMessage());
            return generatePass2(userQuery, resolvedTable, pass1SQL, e.getMessage());
        }
    }

    private String generatePass1(String userQuery, String resolvedTable) {
        String schema = buildCompressedSchema(resolvedTable);
        // Using Llama-3 Instruct tags for Pass 1 too
        String prompt = String.format(
            "<|begin_of_text|><|start_header_id|>system<|end_header_id|>\n" +
            "You are a DuckDB expert. Generate a SQL query to answer the user question.\n" +
            "Return ONLY the SQL inside a [SQL] block.\n" +
            "Schema:\n%s\n" +
            "<|eot_id|><|start_header_id|>user<|end_header_id|>\n" +
            "%s\n" +
            "<|eot_id|><|start_header_id|>assistant<|end_header_id|>\n",
            schema, userQuery
        );
        String rawOutput = runInference(prompt, maxTokens);
        return sqlSanitizer.extractAndValidate(rawOutput);
    }

    private String generatePass2(String userQuery, String resolvedTable,
                                  String failedSQL, String errorMessage) {
        String prompt = dataAwarePromptBuilder.buildPass2Prompt(
                userQuery, resolvedTable, failedSQL, errorMessage);

        // Pass 2 with reasoning takes more tokens
        String rawOutput = runInference(prompt, maxTokens + 200);
        String sql = sqlSanitizer.extractAndValidate(rawOutput);

        // SELF-HEALING: If it's a conversation instead of SQL, let it be (Llama-3 Chat Mode)
        if (!sql.contains("SELECT") && rawOutput.length() > 5) {
            return "MESSAGE:" + rawOutput; 
        }

        log.info("Pass 2 Result: {}", sql);
        return sql;
    }

    // ─── COLUMN VALIDATION ───────────────────────────────────────

    /**
     * Quick check: does the generated SQL reference columns that don't
     * exist in the resolved table? If so, Pass 1 likely hallucinated.
     *
     * WHY PRE-CHECK INSTEAD OF EXECUTE-AND-CATCH:
     * Catching a DuckDB execution error takes ~50ms + wastes a query.
     * This regex-based check takes <1ms and catches the most common
     * failure mode: LLM inventing column names like "gender" when
     * the real column is "customer_segment".
     */
    private boolean hasColumnMismatch(String sql, String tableName) {
        Optional<TableMetadata> tableMeta = registry.findByTableName(tableName);
        if (tableMeta.isEmpty()) return false;

        TableMetadata table = tableMeta.get();
        String upperSQL = sql.toUpperCase();

        // Extract column-like identifiers from WHERE clause
        // Pattern: WHERE/AND/OR followed by an identifier
        java.util.regex.Pattern whereCol = java.util.regex.Pattern.compile(
                "(?:WHERE|AND|OR)\\s+(?:LOWER\\s*\\(\\s*)?\"?([a-zA-Z_][a-zA-Z0-9_]*)\"?",
                java.util.regex.Pattern.CASE_INSENSITIVE);

        java.util.regex.Matcher m = whereCol.matcher(sql);
        while (m.find()) {
            String colCandidate = m.group(1).toLowerCase();

            // Skip SQL keywords
            if (isSQLKeyword(colCandidate)) continue;

            // Check if this column exists in the table
            boolean exists = table.getColumns().stream()
                    .anyMatch(c -> c.getColumnName().toLowerCase().equals(colCandidate));

            if (!exists) {
                log.debug("Column '{}' in SQL not found in table '{}'",
                        colCandidate, tableName);
                return true; // Mismatch found
            }
        }

        return false; // All columns valid
    }

    private boolean isSQLKeyword(String word) {
        return java.util.Set.of(
                "select", "from", "where", "and", "or", "not", "in", "is",
                "null", "true", "false", "as", "on", "join", "left", "right",
                "inner", "outer", "group", "order", "having", "limit", "offset",
                "count", "sum", "avg", "max", "min", "distinct", "between",
                "like", "lower", "upper", "cast", "case", "when", "then",
                "else", "end", "asc", "desc", "by", "exists", "any", "all"
        ).contains(word.toLowerCase());
    }

    // ─── TABLE RESOLUTION (delegated to SmartTableResolver) ──────
    // Old resolveTableName() method is now replaced by SmartTableResolver.
    // Kept as a simple fallback for edge cases.

    private String buildCompressedSchema(String resolvedTable) {
        StringBuilder sb = new StringBuilder();

        if (resolvedTable != null) {
            registry.findByTableName(resolvedTable).ifPresent(t -> {
                sb.append(buildCompactDDL(t));
            });
        } else {
            for (TableMetadata table : registry.getAllTables()) {
                sb.append(buildCompactDDL(table));
                sb.append("\n");
            }
        }
        return sb.toString();
    }

    private String buildCompactDDL(TableMetadata table) {
        StringBuilder sb = new StringBuilder();
        sb.append("CREATE TABLE ").append(table.getTableName())
                .append("(");

        List<ColumnMetadata> cols = table.getColumns();
        for (int i = 0; i < cols.size(); i++) {
            sb.append(cols.get(i).getColumnName())
                    .append(" ").append(cols.get(i).getDataType());
            if (i < cols.size() - 1) sb.append(",");
        }
        sb.append(");");
        return sb.toString();
    }

    // ─── DETERMINISTIC PATH ───────────────────────────────────────

    /**
     * Handles all known simple intents without LLM.
     * Returns SQL string or null if LLM needed.
     */
    private String tryDeterministic(String lower, String table) {

        // ── SHOW COLUMNS ─────────────────────────────────────────
        if (isColumnIntent(lower)) {
            if (table == null) return null;
            return "SELECT column_name, data_type, ordinal_position " +
                   "FROM information_schema.columns " +
                   "WHERE table_name = '" + table + "' " +
                   "ORDER BY ordinal_position";
        }

        // ── COUNT ROWS ───────────────────────────────────────────
        if (isCountIntent(lower)) {
            if (table == null) return null;
            return "SELECT COUNT(*) AS row_count FROM \"" + table + "\"";
        }

        // ── SHOW SAMPLE ──────────────────────────────────────────
        if (isSampleIntent(lower)) {
            if (table == null) return null;
            int limit = extractLimit(lower);
            return "SELECT * FROM \"" + table + "\" LIMIT " + limit;
        }

        // ── FIND DUPLICATES ──────────────────────────────────────
        if (isDuplicateIntent(lower)) {
            if (table == null) return null;
            String col = resolveColumnName(lower, table);
            if (col == null) return null;
            return "SELECT \"" + col + "\", COUNT(*) AS occurrence_count " +
                   "FROM \"" + table + "\" " +
                   "GROUP BY \"" + col + "\" " +
                   "HAVING COUNT(*) > 1 " +
                   "ORDER BY occurrence_count DESC";
        }

        // ── MISSING VALUES ───────────────────────────────────────
        if (isMissingIntent(lower)) {
            if (table == null) return null;
            String col = resolveColumnName(lower, table);
            if (col != null) {
                return "SELECT " +
                       "COUNT(*) AS total_rows, " +
                       "COUNT(\"" + col + "\") AS non_null_count, " +
                       "COUNT(*) - COUNT(\"" + col + "\") AS missing_count, " +
                       "ROUND(100.0 * (COUNT(*) - COUNT(\"" + col + "\")) " +
                       "/ NULLIF(COUNT(*), 0), 2) AS missing_pct " +
                       "FROM \"" + table + "\"";
            } else {
                return buildAllColumnsMissingSql(table);
            }
        }

        // ── DISTINCT VALUES ──────────────────────────────────────
        if (isDistinctIntent(lower)) {
            if (table == null) return null;
            String col = resolveColumnName(lower, table);
            if (col == null) return null;
            return "SELECT DISTINCT \"" + col + "\" AS value, " +
                   "COUNT(*) AS frequency " +
                   "FROM \"" + table + "\" " +
                   "GROUP BY \"" + col + "\" " +
                   "ORDER BY frequency DESC LIMIT 100";
        }

        // ── LIST ALL TABLES ──────────────────────────────────────
        if (lower.contains("list table") ||
            lower.contains("show table") ||
            lower.contains("all table") ||
            lower.contains("uploaded file")) {
            return "SELECT table_name, original_file, file_type, " +
                   "row_count, uploaded_at " +
                   "FROM _sys_table_registry " +
                   "ORDER BY uploaded_at DESC";
        }

        // ── RECENT HISTORY ───────────────────────────────────────
        if (lower.contains("history") ||
            lower.contains("recent quer") ||
            lower.contains("previous quer")) {
            return "SELECT user_query, status, row_count, " +
                   "execution_ms, queried_at " +
                   "FROM _sys_query_history " +
                   "ORDER BY queried_at DESC LIMIT 20";
        }

        return null; // LLM needed
    }

    // ─── INTENT DETECTORS ────────────────────────────────────────

    private boolean isColumnIntent(String q) {
        return q.contains("show column") || q.contains("list column") ||
               q.contains("what column") || q.contains("which column") ||
               q.contains("column name") || q.contains("all column") ||
               q.contains("describe") || q.contains("schema") ||
               q.contains("structure") || q.contains("fields in") ||
               q.contains("what field") || q.contains("headers") ||
               q.contains("show field") || q.contains("list field");
    }

    private boolean isCountIntent(String q) {
        return (q.contains("count") && (q.contains("row") || q.contains("record"))) ||
               (q.contains("how many") && (q.contains("row") || q.contains("record"))) ||
               q.contains("total rows") || q.contains("total records") ||
               q.contains("row count") || q.contains("number of row") ||
               q.contains("number of record");
    }

    private boolean isSampleIntent(String q) {
        return q.contains("show first") || q.contains("first few") ||
               q.contains("preview") || q.contains("sample") ||
               (q.contains("show") && q.contains("row") && !q.contains("missing")) ||
               (q.contains("display") && q.contains("row")) ||
               (q.contains("first") && q.contains("record")) ||
               q.contains("show data") || q.contains("show me data");
    }

    private boolean isDuplicateIntent(String q) {
        return q.contains("duplicate") || q.contains("duplicated") ||
               q.contains("repeated") || q.contains("repeat value");
    }

    private boolean isMissingIntent(String q) {
        return q.contains("missing") || q.contains("null value") ||
               q.contains("empty") || q.contains("blank") ||
               q.contains("not filled") || q.contains("no value");
    }

    private boolean isDistinctIntent(String q) {
        return q.contains("distinct") || q.contains("unique value") ||
               q.contains("different value") || q.contains("possible value") ||
               q.contains("unique entries") || q.contains("all values in");
    }

    // ─── ENTITY RESOLUTION ───────────────────────────────────────

    private String resolveColumnName(String lower, String tableName) {
        Optional<TableMetadata> tableMeta = registry.findByTableName(tableName);
        if (tableMeta.isEmpty()) return null;

        TableMetadata table = tableMeta.get();

        for (var col : table.getColumns()) {
            if (lower.contains(col.getColumnName().toLowerCase()) ||
                lower.contains(col.getOriginalName().toLowerCase())) {
                return col.getColumnName();
            }
        }

        Matcher m = Pattern.compile(
            "(?:in|of|for|on)\\s+(\\w+)(?:\\s+(?:column|field|col))?")
                .matcher(lower);
        if (m.find()) {
            String candidate = m.group(1);
            String resolved = table.resolveColumn(candidate);
            if (resolved != null) return resolved;
        }

        return null;
    }

    private String buildAllColumnsMissingSql(String tableName) {
        Optional<TableMetadata> meta = registry.findByTableName(tableName);
        if (meta.isEmpty()) {
            return "SELECT COUNT(*) AS total_rows FROM \"" + tableName + "\"";
        }

        StringBuilder sb = new StringBuilder("SELECT ");
        var columns = meta.get().getColumns();

        for (int i = 0; i < columns.size(); i++) {
            String col = "\"" + columns.get(i).getColumnName() + "\"";
            sb.append("COUNT(*) - COUNT(").append(col).append(") AS ")
              .append(columns.get(i).getColumnName()).append("_missing");
            if (i < columns.size() - 1) sb.append(", ");
        }

        sb.append(" FROM \"").append(tableName).append("\"");
        return sb.toString();
    }

    private int extractLimit(String lower) {
        Matcher m = Pattern.compile(
            "(?:first|top|limit|last)\\s+(\\d+)").matcher(lower);
        if (m.find()) {
            try { return Integer.parseInt(m.group(1)); }
            catch (NumberFormatException ignored) {}
        }
        return 10;
    }

    // ─── PROMPT BUILDING ──────────────────────────────────────────

    private String buildPass1Prompt(String schema, String userQuery,
                               String resolvedTable) {
        String tableHint = resolvedTable != null
                ? "\nMain table: " + resolvedTable + "\n"
                : "";

        return "### Task\n" +
               "Generate a SQL query to answer [QUESTION]" +
               userQuery + "[/QUESTION]\n\n" +
               "### Rules\n" +
               "- Use DuckDB syntax only\n" +
               "- String comparisons MUST use LOWER() on both sides\n" +
               "  Example: LOWER(column) = LOWER('value')\n" +
               "  NOT: column = 'value'\n" +
               "- Never use SELECT * for column listing\n" +
               tableHint +
               "### Database Schema\n" +
               schema + "\n\n" +
               "### Answer\n" +
               "Given the schema, the SQL query that answers " +
               "[QUESTION]" + userQuery + "[/QUESTION]\n" +
               "[SQL]";
    }

    // ─── LLM INFERENCE ────────────────────────────────────────────

    private String runInference(String prompt, int tokens) {
        StringBuilder output = new StringBuilder();
        long start = System.currentTimeMillis();

        try {
            InferenceParameters params =
                    new InferenceParameters(prompt)
                            .setTemperature(temperature)
                            .setNPredict(tokens)
                            .setStopStrings(
                                    "[/SQL]", 
                                    "<|eot_id|>", 
                                    "<|end_of_text|>",
                                    "<|im_end|>",
                                    "assistant\n",
                                    "###"
                            );

            for (LlamaOutput token : llamaModel.generate(params)) {
                output.append(token);
                String current = output.toString().trim();

                // Aggressive early stopping
                if (current.endsWith("[/SQL]")) break;
                if (current.endsWith("<|eot_id|>")) break;
                
                if (output.length() > 2000) {
                    log.warn("Force stopping — output too long");
                    break;
                }
            }

        } catch (Exception e) {
            throw new QueryProcessingException(
                    "Inference failed: " + e.getMessage(), e);
        }

        log.info("Inference: {}ms", System.currentTimeMillis() - start);
        return output.toString().trim();
    }
  }
