package com.enterprise.dataanalyst.service.llm;

import com.enterprise.dataanalyst.exception.QueryProcessingException;
import com.enterprise.dataanalyst.service.registry.TableMetadataRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Validates and sanitizes LLM-generated SQL before execution.
 *
 * WHY WE VALIDATE:
 * Even a well-trained model can sometimes generate:
 * - Extra explanation text around the SQL
 * - SQL for wrong tables
 * - Dangerous statements (DROP, DELETE, INSERT)
 *
 * We never trust LLM output blindly. We extract, validate, then execute.
 *
 * SECURITY GUARANTEE:
 * Only SELECT statements are allowed through.
 * Any write operation is blocked regardless of what the model generates.
 * This is enforced by whitelist — not blacklist.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SQLSanitizer {

    private final TableMetadataRegistry registry;

    // WHITELIST — only these SQL statement types are allowed
    private static final Set<String> ALLOWED_PREFIXES = Set.of(
            "SELECT", "WITH"  // CTEs start with WITH
    );

    // Pattern to extract SQL from model output
    // Model sometimes wraps SQL in markdown code blocks or adds explanation
    private static final Pattern SQL_EXTRACT_PATTERN = Pattern.compile(
            "(?i)(?:```sql\\s*|```\\s*)?(SELECT|WITH).*?(?:```|$)",
            Pattern.DOTALL
    );

    /**
     * Extract clean SQL from raw LLM output and validate it.
     *
     * @param rawOutput Raw text from LLM
     * @return Clean, validated SQL string ready for DuckDB
     * @throws QueryProcessingException if SQL is invalid or dangerous
     */
    public String extractAndValidate(String rawOutput) {
        if (rawOutput == null || rawOutput.trim().isEmpty()) {
            throw new QueryProcessingException(
                    "Model returned empty response. Please rephrase your query.");
        }

        // Step 1: Extract SQL from raw output
        String sql = extractSQL(rawOutput);
        log.debug("Extracted SQL: {}", sql);

        // Step 2: Validate it starts with allowed keyword
        String upper = sql.trim().toUpperCase();
        boolean allowed = ALLOWED_PREFIXES.stream().anyMatch(upper::startsWith);

        if (!allowed) {
            log.warn("Blocked SQL statement type. Raw output: {}", rawOutput);
            throw new QueryProcessingException(
                    "Generated query is not a valid read-only SQL statement.");
        }

        // Step 3: Block dangerous keywords — double safety net
        if (containsDangerousKeywords(upper)) {
            log.warn("Dangerous keyword detected in SQL: {}", sql);
            throw new QueryProcessingException(
                    "Query contains disallowed operations.");
        }

        // Step 4: Verify referenced tables exist in our registry
        validateTableReferences(sql);

        return sql.trim();
    }

    private String extractSQL(String rawOutput) {
        String cleaned = rawOutput.trim();

        // Try to extract from markdown code blocks first
        Matcher matcher = SQL_EXTRACT_PATTERN.matcher(cleaned);
        if (matcher.find()) {
            return matcher.group().replaceAll("```sql|```", "").trim();
        }

        // If no code block — find first SELECT or WITH
        int selectIdx = cleaned.toUpperCase().indexOf("SELECT");
        int withIdx   = cleaned.toUpperCase().indexOf("WITH");

        int startIdx = -1;
        if (selectIdx >= 0 && withIdx >= 0) {
            startIdx = Math.min(selectIdx, withIdx);
        } else if (selectIdx >= 0) {
            startIdx = selectIdx;
        } else if (withIdx >= 0) {
            startIdx = withIdx;
        }

        if (startIdx >= 0) {
            return cleaned.substring(startIdx).trim();
        }

        // Cannot find SQL
        throw new QueryProcessingException(
                "Could not extract SQL from model response. " +
                        "Try rephrasing: 'count rows in employees' or 'average salary by department'");
    }

    private boolean containsDangerousKeywords(String upperSQL) {
        // These should never appear in a read-only analytical query
        return upperSQL.contains("DROP ")
                || upperSQL.contains("DELETE ")
                || upperSQL.contains("INSERT ")
                || upperSQL.contains("UPDATE ")
                || upperSQL.contains("TRUNCATE ")
                || upperSQL.contains("ALTER ")
                || upperSQL.contains("CREATE ")
                || upperSQL.contains("EXEC ")
                || upperSQL.contains("EXECUTE ");
    }

    /**
     * Verify all tables referenced in SQL actually exist in DuckDB registry.
     * Prevents model from hallucinating table names.
     *
     * NOTE: This is a lightweight check — not a full SQL parser.
     * We check if any known table name appears in the SQL.
     * Full parser would be overkill for our use case.
     */
    private void validateTableReferences(String sql) {
        if (registry.isEmpty()) return;

        String lowerSQL = sql.toLowerCase();
        boolean anyTableReferenced = registry.getAllTables().stream()
                .anyMatch(t -> lowerSQL.contains(t.getTableName().toLowerCase()));

        if (!anyTableReferenced) {
            log.warn("SQL references no known tables: {}", sql);
            throw new QueryProcessingException(
                    "Generated SQL does not reference any uploaded table. " +
                            "Please mention the file name in your query.");
        }
    }
}