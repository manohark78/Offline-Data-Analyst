package com.enterprise.dataanalyst.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Set;

/**
 * Stores profiled intelligence about a single column's actual data values.
 *
 * WHY THIS EXISTS:
 * Schema alone tells us column "customer_segment" is VARCHAR.
 * The profile tells us it contains {"Male", "Female"} — which lets
 * the LLM resolve "show females" to the correct WHERE clause.
 *
 * POPULATED BY: DataProfilerService at upload time (chunk scanning).
 * CONSUMED BY:  DataAwarePromptBuilder for Pass 2 LLM prompts,
 *               SmartTableResolver for data-level table scoring.
 *
 * DATA STAYS LOCAL: Profiles are stored in DuckDB system table
 * _sys_column_profiles. Never leaves the machine.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ColumnProfile {

    private String tableName;        // DuckDB table name
    private String columnName;       // DuckDB column name
    private String dataType;         // VARCHAR, BIGINT, DOUBLE, DATE, BOOLEAN

    /**
     * For CATEGORICAL columns (distinctCount <= maxDistinct):
     * All distinct values, e.g. {"Male", "Female", "Other"}
     * For FREE_TEXT / NUMERIC: empty set.
     */
    private Set<String> distinctValues;

    private long distinctCount;      // Total number of distinct non-null values
    private long nullCount;          // How many NULLs in this column
    private long totalCount;         // Total row count

    /**
     * For numeric columns: string representation of min value.
     * For date columns: earliest date as string.
     * For others: null.
     */
    private String minValue;

    /**
     * For numeric columns: string representation of max value.
     * For date columns: latest date as string.
     * For others: null.
     */
    private String maxValue;

    /**
     * Pattern classification:
     * CATEGORICAL  — few distinct values (≤ threshold), useful for filtering
     * NUMERIC_RANGE — numeric type, useful for range queries
     * DATE_RANGE   — date type, useful for time-based queries
     * FREE_TEXT    — too many distinct values to enumerate (names, addresses)
     * BOOLEAN_FLAG — boolean column
     */
    private String dominantPattern;

    /**
     * First 5 non-null unique sample values.
     * Used in LLM prompts as inline examples.
     * e.g. ["Priya Sharma", "Amit Patel", "Sunita Rao"]
     */
    private List<String> sampleValues;

    /**
     * Check if this column's profiled data contains a specific value.
     * Case-insensitive match against distinct values.
     */
    public boolean containsValue(String value) {
        if (distinctValues == null || distinctValues.isEmpty()) return false;
        String lower = value.toLowerCase().trim();
        return distinctValues.stream()
                .anyMatch(v -> v.toLowerCase().contains(lower)
                        || lower.contains(v.toLowerCase()));
    }

    /**
     * Check if column is categorical (few distinct values — useful for filtering).
     */
    public boolean isCategorical() {
        return "CATEGORICAL".equals(dominantPattern);
    }

    /**
     * Check if column is numeric with known range.
     */
    public boolean isNumericRange() {
        return "NUMERIC_RANGE".equals(dominantPattern);
    }

    /**
     * Build a compact annotation string for LLM prompt DDL comments.
     * Example outputs:
     *   "values: Male, Female"
     *   "range: 5 to 85"
     *   "e.g.: Priya Sharma, Amit Patel (48291 unique)"
     */
    public String toAnnotation() {
        if (isCategorical() && distinctValues != null && !distinctValues.isEmpty()) {
            return "values: " + String.join(", ", distinctValues);
        }
        if (isNumericRange() && minValue != null && maxValue != null) {
            return "range: " + minValue + " to " + maxValue;
        }
        if ("DATE_RANGE".equals(dominantPattern) && minValue != null && maxValue != null) {
            return "date range: " + minValue + " to " + maxValue;
        }
        if (sampleValues != null && !sampleValues.isEmpty()) {
            String samples = String.join(", ", sampleValues.subList(
                    0, Math.min(3, sampleValues.size())));
            return "e.g.: " + samples + " (" + distinctCount + " unique)";
        }
        return null;
    }
}
