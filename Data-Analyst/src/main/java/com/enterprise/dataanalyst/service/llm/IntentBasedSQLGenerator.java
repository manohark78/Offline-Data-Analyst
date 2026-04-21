package com.enterprise.dataanalyst.service.llm;

import com.enterprise.dataanalyst.exception.QueryProcessingException;
import com.enterprise.dataanalyst.nlp.IntentAction;
import com.enterprise.dataanalyst.nlp.QueryIntent;
import com.enterprise.dataanalyst.service.registry.TableMetadataRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.Optional;

/**
 * Generates deterministic DuckDB SQL from a classified QueryIntent.
 *
 * WHY DETERMINISTIC SQL GENERATION:
 * AI classified the intent correctly.
 * We generate SQL mechanically from the intent — no AI involvement here.
 * This gives us:
 * - Zero hallucination (pure if-else logic)
 * - Exact DuckDB syntax always
 * - Auditable SQL (always shown to user)
 * - Testable (unit test each action independently)
 *
 * Java 11 compatible — all if-else, no switch expressions, no text blocks.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class IntentBasedSQLGenerator {

    private final TableMetadataRegistry registry;

    @Value("${app.query.default-limit:100}")
    private int defaultLimit;

    public String generate(QueryIntent intent) {
        validate(intent);

        String table   = intent.getTargetTable();
        String column  = intent.getTargetColumn();
        String groupBy = intent.getGroupByColumn();
        int limit      = intent.getLimit() != null ? intent.getLimit() : defaultLimit;

        // Quoted identifiers — safe for any column/table name including reserved words
        String qt = quoted(table);
        String qc = column != null ? quoted(column) : null;
        String qg = groupBy != null ? quoted(groupBy) : null;

        String sql;
        IntentAction action = intent.getAction();

        if (action == IntentAction.COUNT_ROWS) {
            sql = "SELECT COUNT(*) AS row_count FROM " + qt;

        } else if (action == IntentAction.SHOW_COLUMNS) {
            sql = "SELECT column_name, data_type, ordinal_position " +
                    "FROM information_schema.columns " +
                    "WHERE table_name = '" + table + "' " +
                    "ORDER BY ordinal_position";

        } else if (action == IntentAction.SHOW_SAMPLE) {
            sql = "SELECT * FROM " + qt + " LIMIT " + limit;

        } else if (action == IntentAction.FIND_DUPLICATES) {
            if (qc == null) {
                throw new QueryProcessingException(
                        "Please specify which column to check for duplicates. " +
                                "Example: 'find duplicates in employee_id'");
            }
            sql = "SELECT " + qc + ", COUNT(*) AS occurrence_count " +
                    "FROM " + qt + " " +
                    "GROUP BY " + qc + " " +
                    "HAVING COUNT(*) > 1 " +
                    "ORDER BY occurrence_count DESC";

        } else if (action == IntentAction.SHOW_MISSING_VALUES) {
            if (qc == null) {
                throw new QueryProcessingException(
                        "Please specify which column to check for missing values. " +
                                "Example: 'show missing values in phone'");
            }
            sql = "SELECT " +
                    "COUNT(*) AS total_rows, " +
                    "COUNT(" + qc + ") AS non_null_count, " +
                    "COUNT(*) - COUNT(" + qc + ") AS missing_count, " +
                    "ROUND(100.0 * (COUNT(*) - COUNT(" + qc + ")) " +
                    "/ NULLIF(COUNT(*), 0), 2) AS missing_pct " +
                    "FROM " + qt;

        } else if (action == IntentAction.AGGREGATE) {
            sql = buildAggregateSql(qt, qc, qg, intent, limit);

        } else if (action == IntentAction.SHOW_DISTINCT) {
            if (qc == null) {
                throw new QueryProcessingException(
                        "Please specify which column to show distinct values for. " +
                                "Example: 'distinct values in department'");
            }
            sql = "SELECT DISTINCT " + qc + " AS value, COUNT(*) AS frequency " +
                    "FROM " + qt + " " +
                    "GROUP BY " + qc + " " +
                    "ORDER BY frequency DESC " +
                    "LIMIT " + limit;

        } else if (action == IntentAction.FIND_FILES_WITH_COLUMN) {
            // Handled separately via registry — not a DuckDB query
            return null;

        } else {
            throw new QueryProcessingException(
                    "No SQL template available for action: " + action);
        }

        log.info("Generated SQL: {}", sql);
        return sql;
    }

    private String buildAggregateSql(String qt, String qc, String qg,
                                     QueryIntent intent, int limit) {
        if (qc == null) {
            throw new QueryProcessingException(
                    "Please specify which column to aggregate. " +
                            "Example: 'average salary by department'");
        }

        String aggExpr = buildAggExpression(intent, qc);
        String alias   = intent.getAggregateFunction().name().toLowerCase()
                + "_" + intent.getTargetColumn();

        if (qg != null) {
            return "SELECT " + qg + ", " + aggExpr + " AS " + alias + " " +
                    "FROM " + qt + " " +
                    "WHERE " + qc + " IS NOT NULL " +
                    "GROUP BY " + qg + " " +
                    "ORDER BY " + alias + " DESC " +
                    "LIMIT " + limit;
        } else {
            return "SELECT " + aggExpr + " AS " + alias + " " +
                    "FROM " + qt + " " +
                    "WHERE " + qc + " IS NOT NULL";
        }
    }

    private String buildAggExpression(QueryIntent intent, String qc) {
        // Java 11 compatible if-else instead of switch expression
        if (intent.getAggregateFunction() == null) {
            return "COUNT(" + qc + ")";
        }

        switch (intent.getAggregateFunction()) {
            case AVG:
                return "ROUND(AVG(TRY_CAST(" + qc + " AS DOUBLE)), 2)";
            case SUM:
                return "SUM(TRY_CAST(" + qc + " AS DOUBLE))";
            case MAX:
                return "MAX(" + qc + ")";
            case MIN:
                return "MIN(" + qc + ")";
            case COUNT_DISTINCT:
                return "COUNT(DISTINCT " + qc + ")";
            default:
                return "COUNT(" + qc + ")";
        }
    }

    private void validate(QueryIntent intent) {
        if (intent.getAction() == IntentAction.UNKNOWN) {
            throw new QueryProcessingException(intent.getInterpretationSummary());
        }
        if (intent.getAction() != IntentAction.FIND_FILES_WITH_COLUMN
                && intent.getTargetTable() == null) {
            throw new QueryProcessingException(
                    "Could not determine which table to query. " +
                            "Please mention the file name. Example: 'count rows in employees'");
        }
    }

    private String quoted(String identifier) {
        if (identifier == null) return null;
        return "\"" + identifier.replace("\"", "") + "\"";
    }
}