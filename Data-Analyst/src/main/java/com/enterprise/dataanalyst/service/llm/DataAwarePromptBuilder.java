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
    /**
     * Build the Llama-3-Instruct prompt with full data context and intent instructions.
     */
    public String buildPass2Prompt(String userQuery,
                                    String resolvedTable,
                                    String failedSQL,
                                    String errorMessage) {

        StringBuilder schema = new StringBuilder();
        if (resolvedTable != null) {
            schema.append(buildEnrichedDDL(resolvedTable));
        } else {
            for (TableMetadata table : tableRegistry.getAllTables()) {
                schema.append(buildEnrichedDDL(table.getTableName())).append("\n");
            }
        }

        return String.format(
            "<|begin_of_text|><|start_header_id|>system<|end_header_id|>\n" +
            "You are an expert Offline Data Analyst. Your goal is to help users analyze their enterprise data.\n" +
            "You have two modes of operation:\n" +
            "1. **CHAT MODE**: If the user is greeting you, asking about your capabilities, or asking general questions, reply naturally.\n" +
            "2. **QUERY MODE**: If the user asks for data analysis, return the correct SQL query in a [SQL]...[/SQL] block.\n" +
            "\n" +
            "CRITICAL RULES FOR QUERY MODE:\n" +
            "- Database: DuckDB\n" +
            "- Schema: See below. Each column has a comment showing actual data values or ranges found in the file.\n" +
            "- Intent Mapping: If the user says a word like 'females', 'adults', or 'expensive', look at the data values in the comments to find the matching column. Do NOT invent columns.\n" +
            "- Reasoning: Always think about which columns match the user's intent before writing SQL.\n" +
            "- Safety: Never generate queries that modify data (no DROP, DELETE, UPDATE).\n" +
            "- Fallback: If you cannot answer the question from the data provided, explain why.\n" +
            "\n" +
            "### Database Schema\n%s" +
            "<|eot_id|><|start_header_id|>user<|end_header_id|>\n" +
            "%s%s" +
            "User Question: %s\n" +
            "<|eot_id|><|start_header_id|>assistant<|end_header_id|>\n",
            schema.toString(),
            (failedSQL != null ? "My previous SQL attempt failed: " + failedSQL + "\n" : ""),
            (errorMessage != null ? "The error was: " + sanitizeError(errorMessage) + "\n" : ""),
            userQuery
        );
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
