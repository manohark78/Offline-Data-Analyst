// nlp/QueryIntent.java
package com.enterprise.dataanalyst.nlp;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Structured representation of what the user wants to do.
 *
 * This is the BRIDGE between natural language and SQL.
 * The intent parser fills this in; the SQL generator reads it.
 *
 * NULLS ARE INTENTIONAL:
 * Not every intent has all fields. SHOW_COLUMNS doesn't need
 * targetColumn. COUNT_ROWS doesn't need groupByColumn.
 * Nulls indicate "not applicable for this action".
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class QueryIntent {

    private IntentAction action;

    /**
     * The DuckDB table name to query.
     * Null = not yet resolved (IntentEnrichmentService will resolve it).
     */
    private String targetTable;

    /**
     * The column to operate on.
     * E.g., "salary" for AVG(salary), "employee_id" for duplicate check.
     */
    private String targetColumn;

    /**
     * Column to group by in aggregation queries.
     * E.g., "department" in "average salary by department".
     */
    private String groupByColumn;

    /**
     * Aggregate function: AVG, SUM, MAX, MIN, COUNT_DISTINCT.
     * Only populated when action == AGGREGATE.
     */
    private AggregateFunction aggregateFunction;

    /**
     * How many rows to return. Default applied in SQLGeneratorService.
     */
    private Integer limit;

    /**
     * Original user query — stored for transparency/debugging.
     */
    private String rawQuery;

    /**
     * 0.0 to 1.0. If below threshold, return clarification message instead of SQL.
     */
    private double confidence;

    /**
     * Human-readable message explaining what the system understood.
     * Always shown to user so they can correct misunderstandings.
     */
    private String interpretationSummary;
}