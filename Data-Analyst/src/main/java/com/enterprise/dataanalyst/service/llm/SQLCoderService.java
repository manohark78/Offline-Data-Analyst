package com.enterprise.dataanalyst.service.llm;

import com.enterprise.dataanalyst.exception.QueryProcessingException;
import com.enterprise.dataanalyst.model.TableMetadata;
import com.enterprise.dataanalyst.service.registry.TableMetadataRegistry;
import de.kherud.llama.InferenceParameters;
import de.kherud.llama.LlamaModel;
import de.kherud.llama.LlamaOutput;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * SQL generation service — two paths:
 *
 * PATH 1 — DETERMINISTIC (fast, ~0ms):
 *   Known intents intercepted before LLM.
 *   Schema queries, count, preview, duplicates,
 *   missing values — all handled deterministically.
 *   Table auto-resolved from registry.
 *
 * PATH 2 — LLM (slow, ~5-8s):
 *   Complex queries only.
 *   Passes enriched prompt with resolved table name.
 *
 * WHY THIS DESIGN:
 * 80% of user queries are simple intent types.
 * Bypassing LLM for these makes them instant.
 * LLM reserved for genuinely complex queries.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SQLCoderService {

    private final LlamaModel llamaModel;
    private final SchemaContextBuilder schemaContextBuilder;
    private final SQLSanitizer sqlSanitizer;
    private final TableMetadataRegistry registry;

    @Value("${app.llm.temperature:0.1}")
    private float temperature;

    @Value("${app.llm.max-tokens:300}")
    private int maxTokens;

    // ─── PUBLIC API ───────────────────────────────────────────────

    public String generateSQL(String userQuery) {
        log.info("Processing query: '{}'", userQuery);
        String lower = userQuery.toLowerCase().trim();

        // Step 1: Resolve table name upfront
        String resolvedTable = resolveTableName(lower);
        log.info("Resolved table: '{}'", resolvedTable);

        // Step 2: Try deterministic path first
        String deterministicSQL = tryDeterministic(lower, resolvedTable);
        if (deterministicSQL != null) {
            log.info("Deterministic SQL (LLM bypassed): {}", deterministicSQL);
            return deterministicSQL;
        }

        // Step 3: LLM path — complex queries
        log.info("Routing to LLM for complex query...");
        return generateWithLLM(userQuery, resolvedTable);
    }

    // ─── TABLE RESOLUTION ─────────────────────────────────────────

    /**
     * Resolves table name from user query.
     *
     * Priority:
     * 1. Direct match of known table name in query
     * 2. File name match (without extension)
     * 3. Fuzzy partial match
     * 4. Auto-select if only one table loaded
     * 5. null — LLM will try to figure it out
     */
    private String resolveTableName(String lower) {
        Collection<TableMetadata> tables = registry.getAllTables();

        // Priority 1 — exact table name match
        for (TableMetadata table : tables) {
            if (lower.contains(table.getTableName().toLowerCase())) {
                return table.getTableName();
            }
        }

        // Priority 2 — file name match
        for (TableMetadata table : tables) {
            String base = table.getOriginalFileName()
                    .replaceAll("\\.[^.]+$", "").toLowerCase();
            if (lower.contains(base)) {
                return table.getTableName();
            }
        }

        // Priority 3 — fuzzy partial match
        for (TableMetadata table : tables) {
            String tName = table.getTableName().toLowerCase();
            // Check if any word in query partially matches table name
            String[] words = lower.split("\\s+");
            for (String word : words) {
                if (word.length() > 3 &&
                    (tName.contains(word) || word.contains(tName))) {
                    return table.getTableName();
                }
            }
        }

        // Priority 4 — only one table loaded
        if (tables.size() == 1) {
            String auto = tables.iterator().next().getTableName();
            log.debug("Auto-selected single table: '{}'", auto);
            return auto;
        }

        return null;
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
                // Specific column
                return "SELECT " +
                       "COUNT(*) AS total_rows, " +
                       "COUNT(\"" + col + "\") AS non_null_count, " +
                       "COUNT(*) - COUNT(\"" + col + "\") AS missing_count, " +
                       "ROUND(100.0 * (COUNT(*) - COUNT(\"" + col + "\")) " +
                       "/ NULLIF(COUNT(*), 0), 2) AS missing_pct " +
                       "FROM \"" + table + "\"";
            } else {
                // All columns summary
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

    /**
     * Resolves column name from query against actual table schema.
     */
    private String resolveColumnName(String lower, String tableName) {
        Optional<TableMetadata> tableMeta = registry.findByTableName(tableName);
        if (tableMeta.isEmpty()) return null;

        TableMetadata table = tableMeta.get();

        // Direct match against known column names
        for (var col : table.getColumns()) {
            if (lower.contains(col.getColumnName().toLowerCase()) ||
                lower.contains(col.getOriginalName().toLowerCase())) {
                return col.getColumnName();
            }
        }

        // Pattern: "in X column" / "of X field"
        Matcher m = Pattern.compile(
            "(?:in|of|for|on)\\s+(\\w+)(?:\\s+(?:column|field|col))?")
                .matcher(lower);
        if (m.find()) {
            String candidate = m.group(1);
            // Validate against table columns
            String resolved = table.resolveColumn(candidate);
            if (resolved != null) return resolved;
        }

        return null;
    }

    /**
     * Builds missing values summary for ALL columns in a table.
     * Used when no specific column mentioned.
     */
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
        return 10; // default
    }

    // ─── LLM PATH ─────────────────────────────────────────────────

    /**
     * LLM path — for complex queries only.
     * Enriches prompt with resolved table name for better accuracy.
     */
    private String generateWithLLM(String userQuery, String resolvedTable) {
        // Build schema — if table resolved, use only that table's schema
        String schema = resolvedTable != null
                ? schemaContextBuilder.buildTableSchema(resolvedTable)
                : schemaContextBuilder.buildFullSchema();

        String prompt = buildPrompt(schema, userQuery, resolvedTable);
        log.debug("LLM prompt:\n{}", prompt);

        String rawOutput = runInference(prompt);
        log.debug("LLM raw output: {}", rawOutput);

        return sqlSanitizer.extractAndValidate(rawOutput);
    }

    private String buildPrompt(String schema, String userQuery,
                                String resolvedTable) {
        String tableHint = resolvedTable != null
                ? "\nNote: The main table to query is: " + resolvedTable
                : "";

        return "### Task\n" +
               "Generate a SQL query to answer [QUESTION]" +
               userQuery + "[/QUESTION]\n\n" +
               "### Rules\n" +
               "- Use only DuckDB syntax\n" +
               "- Only use columns from the schema below\n" +
               "- For column listing: use information_schema.columns\n" +
               "- For counting: use SELECT COUNT(*)\n" +
               "- Never use SELECT * when user asks for column names\n" +
               tableHint + "\n\n" +
               "### Database Schema\n" +
               "The query will run on a database with the following schema:\n" +
               schema + "\n\n" +
               "### Answer\n" +
               "Given the database schema, here is the SQL query that " +
               "answers [QUESTION]" + userQuery + "[/QUESTION]\n" +
               "[SQL]";
    }

    private String runInference(String prompt) {
        StringBuilder output = new StringBuilder();
        long start = System.currentTimeMillis();

        try {
            InferenceParameters params = new InferenceParameters(prompt)
                    .setTemperature(temperature)
                    .setNPredict(maxTokens)
                    .setStopStrings("[/SQL]", "###", "\n\n");

            for (LlamaOutput token : llamaModel.generate(params)) {
                output.append(token);
                String current = output.toString().trim();
                if (current.endsWith(";") || current.contains("[/SQL]")) break;
            }

        } catch (Exception e) {
            log.error("LLM inference failed: {}", e.getMessage());
            throw new QueryProcessingException(
                "SQL generation failed: " + e.getMessage(), e);
        }

        log.info("LLM inference: {}ms", System.currentTimeMillis() - start);
        return output.toString().trim();
    }
  }
