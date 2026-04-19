// service/query/SQLGeneratorService.java
package com.enterprise.dataanalyst.service.query;

import com.enterprise.dataanalyst.exception.QueryProcessingException;
import com.enterprise.dataanalyst.nlp.IntentAction;
import com.enterprise.dataanalyst.nlp.QueryIntent;
import com.enterprise.dataanalyst.service.registry.TableMetadataRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.Set;

/**
 * Generates deterministic, parameterized SQL from a structured QueryIntent.
 *
 * THIS IS THE TRUST BOUNDARY.
 * No AI, no randomness — every output is computed mechanically from the intent.
 * The user's data is ONLY touched here, at the SQL execution layer.
 *
 * SQL INJECTION PREVENTION:
 * Table and column names come from our metadata registry (which we control).
 * They were sanitized at upload time by TableNameSanitizer.
 * We wrap all identifiers in double quotes — DuckDB's mechanism for safe identifiers.
 * User string values NEVER appear in SQL strings; they are not used in WHERE clauses yet.
 *
 * DESIGN: Each action maps to exactly one SQL template.
 * Adding a new action = adding one case here. No abstraction needed.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SQLGeneratorService {

    private final TableMetadataRegistry registry;

    @Value("${app.query.default-limit:100}")
    private int defaultLimit;

    // Actions that require a column name
    private static final Set<IntentAction> COLUMN_REQUIRED = Set.of(
            IntentAction.FIND_DUPLICATES,
            IntentAction.SHOW_MISSING_VALUES,
            IntentAction.SHOW_DISTINCT
    );

    public String generate(QueryIntent intent) {
        validateIntent(intent);

        String table = intent.getTargetTable();
        String column = intent.getTargetColumn();
        String groupBy = intent.getGroupByColumn();
        int limit = intent.getLimit() != null ? intent.getLimit() : defaultLimit;

        // All identifiers are double-quoted — safe for any column name
        String qt = quoted(table);
        String qc = column != null ? quoted(column) : null;
        String qg = groupBy != null ? quoted(groupBy) : null;

        String sql = switch (intent.getAction()) {

            case COUNT_ROWS -> String.format(
                    "SELECT COUNT(*) AS row_count FROM %s", qt);

            case SHOW_COLUMNS -> String.format(
                    "SELECT column_name, data_type, ordinal_position " +
                    "FROM information_schema.columns " +
                    "WHERE table_name = '%s' " +
                    "ORDER BY ordinal_position", table); // table name in WHERE value — safe string

            case SHOW_SAMPLE -> String.format(
                    "SELECT * FROM %s LIMIT %d", qt, limit);

            case FIND_DUPLICATES -> String.format(
                    "SELECT %s, COUNT(*) AS occurrence_count " +
                    "FROM %s " +
                    "GROUP BY %s " +
                    "HAVING COUNT(*) > 1 " +
                    "ORDER BY occurrence_count DESC",
                    qc, qt, qc);

            case SHOW_MISSING_VALUES -> String.format(
                    "SELECT " +
                    "COUNT(*) AS total_rows, " +
                    "COUNT(%s) AS non_null_count, " +
                    "COUNT(*) - COUNT(%s) AS missing_count, " +
                    "ROUND(100.0 * (COUNT(*) - COUNT(%s)) / NULLIF(COUNT(*), 0), 2) AS missing_pct " +
                    "FROM %s",
                    qc, qc, qc, qt);

            case AGGREGATE -> generateAggregateSql(qt, qc, qg, intent, limit);

            case SHOW_DISTINCT -> String.format(
                    "SELECT DISTINCT %s AS value, COUNT(*) AS frequency " +
                    "FROM %s " +
                    "GROUP BY %s " +
                    "ORDER BY frequency DESC " +
                    "LIMIT %d",
                    qc, qt, qc, limit);

            case FIND_FILES_WITH_COLUMN ->
                    null; // Handled by QueryExecutorService via registry — not a DuckDB query

            default -> throw new QueryProcessingException(
                    "No SQL template for action: " + intent.getAction());
        };

        if (sql != null) {
            log.info("Generated SQL: {}", sql);
        }
        return sql;
    }

    private String generateAggregateSql(String qt, String qc, String qg,
                                         QueryIntent intent, int limit) {
        if (qc == null) {
            throw new QueryProcessingException(
                    "Please specify which column to aggregate. " +
                    "Example: 'average salary by department'");
        }

        String aggExpr = buildAggExpression(intent, qc);
        String colAlias = intent.getAggregateFunction().name().toLowerCase() + "_" +
                intent.getTargetColumn();

        if (qg != null) {
            // Grouped aggregate: SELECT dept, AVG(salary) FROM t GROUP BY dept
            return String.format(
                    "SELECT %s, %s AS %s " +
                    "FROM %s " +
                    "WHERE %s IS NOT NULL " +
                    "GROUP BY %s " +
                    "ORDER BY %s DESC " +
                    "LIMIT %d",
                    qg, aggExpr, colAlias, qt, qc, qg, colAlias, limit);
        } else {
            // Simple aggregate: SELECT AVG(salary) FROM t
            return String.format(
                    "SELECT %s AS %s FROM %s WHERE %s IS NOT NULL",
                    aggExpr, colAlias, qt, qc);
        }
    }

    private String buildAggExpression(QueryIntent intent, String qc) {
        return switch (intent.getAggregateFunction()) {
            case AVG -> "ROUND(AVG(TRY_CAST(" + qc + " AS DOUBLE)), 2)";
            case SUM -> "SUM(TRY_CAST(" + qc + " AS DOUBLE))";
            case MAX -> "MAX(" + qc + ")";
            case MIN -> "MIN(" + qc + ")";
            case COUNT_DISTINCT -> "COUNT(DISTINCT " + qc + ")";
        };
    }

    /**
     * Wrap a sanitized identifier in double quotes.
     * DuckDB (and ANSI SQL) uses double quotes for identifiers.
     * This is the last line of defense against identifier injection.
     */
    private String quoted(String identifier) {
        if (identifier == null) return null;
        // Strip any double quotes already present to prevent escaping issues
        return "\"" + identifier.replace("\"", "") + "\"";
    }

    private void validateIntent(QueryIntent intent) {
        if (intent.getAction() == IntentAction.UNKNOWN) {
            throw new QueryProcessingException(intent.getInterpretationSummary());
        }

        if (intent.getAction() != IntentAction.FIND_FILES_WITH_COLUMN) {
            if (intent.getTargetTable() == null) {
                throw new QueryProcessingException(
                        "Could not determine which file/table to query. " +
                        "Please specify the file name. " +
                        "Example: 'count rows in employees'");
            }
        }

        if (COLUMN_REQUIRED.contains(intent.getAction()) && intent.getTargetColumn() == null) {
            throw new QueryProcessingException(
                    "Please specify the column name. " +
                    "Example: 'find duplicates in employee_id'");
        }
    }
}