package com.enterprise.dataanalyst.service.llm;

import com.enterprise.dataanalyst.model.ColumnProfile;
import com.enterprise.dataanalyst.model.TableMetadata;
import com.enterprise.dataanalyst.service.profiler.ColumnProfileRegistry;
import com.enterprise.dataanalyst.service.registry.TableMetadataRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Builds the data-aware LLM prompt for Pass 2.
 *
 * WHY PASS 2 EXISTS:
 * Pass 1 uses schema-only DDL — it knows column names and types but NOT values.
 * When the user says "show females" and there's no "gender" column, Pass 1 fails.
 *
 * Pass 2 enriches the DDL with actual data values from the profiler:
 *   customer_segment VARCHAR  -- values: Male, Female
 *   age BIGINT               -- range: 5 to 85
 *   city VARCHAR             -- values: Mumbai, Delhi, Chennai
 *
 * Now the LLM can reason:
 *   "females" → customer_segment = 'Female'
 *   "adults"  → age >= 18
 *   "Mumbai"  → city = 'Mumbai'
 *
 * NO HARDCODED SYNONYMS:
 * The LLM already understands human concepts. We just show it the data.
 * It can handle "senior citizens" (age >= 60), "teenagers" (age 13-19),
 * "high spenders" (amount > threshold), etc. — ALL dynamically.
 *
 * PROMPT BUDGET:
 * Pass 2 prompt is larger (~600-800 tokens) but stays within the
 * 2048 context window. We only annotate relevant columns, not all.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class DataAwarePromptBuilder {

    private final TableMetadataRegistry tableRegistry;
    private final ColumnProfileRegistry profileRegistry;

    /**
     * Build the Pass 2 prompt with full data context.
     *
     * @param userQuery     Original natural language query
     * @param resolvedTable Best-guess table name (from SmartTableResolver)
     * @param failedSQL     The SQL that failed in Pass 1 (if any)
     * @param errorMessage  The DuckDB error message (if any)
     * @return Complete prompt ready for LLM inference
     */
    public String buildPass2Prompt(String userQuery,
                                    String resolvedTable,
                                    String failedSQL,
                                    String errorMessage) {

        StringBuilder prompt = new StringBuilder();

        // ── Task section ────────────────────────────────────────
        prompt.append("### Task\n")
              .append("Generate a SQL query to answer [QUESTION]")
              .append(userQuery)
              .append("[/QUESTION]\n\n");

        // ── Previous failure context (helps LLM avoid same mistake) ──
        if (failedSQL != null && errorMessage != null) {
            prompt.append("### Previous Attempt Failed\n")
                  .append("Error: ").append(sanitizeError(errorMessage)).append("\n")
                  .append("Failed SQL: ").append(failedSQL).append("\n\n");
        }

        // ── Enriched schema with data annotations ───────────────
        prompt.append("### Database Schema (with data context)\n");

        if (resolvedTable != null) {
            prompt.append(buildEnrichedDDL(resolvedTable));
        } else {
            // No resolved table — show all tables (enriched)
            for (TableMetadata table : tableRegistry.getAllTables()) {
                prompt.append(buildEnrichedDDL(table.getTableName()));
                prompt.append("\n");
            }
        }

        // ── Rules section ───────────────────────────────────────
        prompt.append("\n### Rules\n")
              .append("- Use DuckDB SQL syntax\n")
              .append("- String comparisons MUST use LOWER() on both sides\n")
              .append("  Example: WHERE LOWER(col) = LOWER('value')\n")
              .append("- Use ONLY columns that exist in the schema above\n")
              .append("- If a concept (like gender, age group, category) is not a ")
              .append("direct column, derive it from existing columns and their values\n");

        if (resolvedTable != null) {
            prompt.append("- Main table: ").append(resolvedTable).append("\n");
        }

        // ── Answer section ──────────────────────────────────────
        prompt.append("\n### Answer\n")
              .append("Given the schema and data context, the SQL query that answers ")
              .append("[QUESTION]").append(userQuery).append("[/QUESTION]\n")
              .append("[SQL]");

        String finalPrompt = prompt.toString();
        log.debug("Pass 2 prompt ({} chars):\n{}", finalPrompt.length(), finalPrompt);
        return finalPrompt;
    }

    /**
     * Build enriched DDL for a single table — column names + data annotations.
     *
     * EXAMPLE OUTPUT:
     * CREATE TABLE customers (
     *     customer_id BIGINT,          -- range: 1 to 50000
     *     name VARCHAR,                -- e.g.: Priya Sharma, Amit Patel (48291 unique)
     *     age BIGINT,                  -- range: 5 to 85
     *     city VARCHAR,                -- values: Mumbai, Delhi, Chennai, Bangalore, Pune
     *     customer_segment VARCHAR     -- values: Male, Female
     * );
     */
    private String buildEnrichedDDL(String tableName) {
        Optional<TableMetadata> tableMeta = tableRegistry.findByTableName(tableName);
        if (tableMeta.isEmpty()) return "-- Table '" + tableName + "' not found\n";

        TableMetadata table = tableMeta.get();
        List<ColumnProfile> profiles = profileRegistry.getTableProfiles(tableName);

        StringBuilder sb = new StringBuilder();
        sb.append("CREATE TABLE ").append(table.getTableName()).append(" (\n");

        var columns = table.getColumns();
        for (int i = 0; i < columns.size(); i++) {
            var col = columns.get(i);
            sb.append("    ")
              .append(col.getColumnName())
              .append(" ").append(col.getDataType());

            // Add data annotation as SQL comment
            String annotation = getAnnotation(tableName, col.getColumnName(), profiles);
            if (annotation != null) {
                // Pad to align comments
                int padding = Math.max(1, 30 - col.getColumnName().length()
                                            - col.getDataType().length());
                sb.append(" ".repeat(padding))
                  .append("-- ").append(annotation);
            }

            if (i < columns.size() - 1) sb.append(",");
            sb.append("\n");
        }

        sb.append(");\n");
        return sb.toString();
    }

    /**
     * Get the data annotation for a column from its profile.
     * Returns null if no profile exists or annotation would be uninformative.
     */
    private String getAnnotation(String tableName, String columnName,
                                  List<ColumnProfile> profiles) {
        Optional<ColumnProfile> profileOpt = profiles.stream()
                .filter(p -> p.getColumnName().equals(columnName))
                .findFirst();

        if (profileOpt.isEmpty()) return null;

        return profileOpt.get().toAnnotation();
    }

    /**
     * Clean up error message for the prompt.
     * Remove stack traces, keep only the meaningful error.
     */
    private String sanitizeError(String error) {
        if (error == null) return "Unknown error";

        // Extract the core message — strip Java exception prefixes
        String cleaned = error;
        if (cleaned.contains(": ")) {
            // "QueryProcessingException: Database execution failed: Column X not found"
            // → "Column X not found"
            String[] parts = cleaned.split(": ");
            cleaned = parts[parts.length - 1];
        }

        // Truncate very long errors
        if (cleaned.length() > 200) {
            cleaned = cleaned.substring(0, 200) + "...";
        }

        return cleaned.trim();
    }
}
