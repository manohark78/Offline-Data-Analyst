// util/DataTypeInferrer.java
package com.enterprise.dataanalyst.util;

import lombok.extern.slf4j.Slf4j;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Arrays;
import java.util.List;

/**
 * Infers the SQL data type of a column by sampling its values.
 *
 * ALGORITHM:
 * We try to parse each sample value as types in priority order:
 *   BOOLEAN → BIGINT → DOUBLE → DATE → VARCHAR
 *
 * "Can parse as BIGINT" means: every non-null sample parses as a whole number.
 * If even ONE value fails, we fall to the next type.
 * VARCHAR is the fallback — every value is representable as a string.
 *
 * WHY SAMPLE, NOT ALL ROWS:
 * For a 1M-row file, type inference on all rows is slow. Sampling the first
 * N rows is a reasonable tradeoff. Configurable via SAMPLE_SIZE.
 */
@Slf4j
public class DataTypeInferrer {

    private static final int SAMPLE_SIZE = 200;
    private static final double NULL_RATIO_THRESHOLD = 0.95; // if 95%+ null, still infer type

    private static final List<DateTimeFormatter> DATE_FORMATS = Arrays.asList(
            DateTimeFormatter.ISO_LOCAL_DATE,           // 2024-01-15
            DateTimeFormatter.ofPattern("MM/dd/yyyy"),  // 01/15/2024
            DateTimeFormatter.ofPattern("dd/MM/yyyy"),  // 15/01/2024
            DateTimeFormatter.ofPattern("yyyy/MM/dd"),  // 2024/01/15
            DateTimeFormatter.ofPattern("dd-MM-yyyy"),  // 15-01-2024
            DateTimeFormatter.ofPattern("MM-dd-yyyy")   // 01-15-2024
    );

    private DataTypeInferrer() {}

    /**
     * Infer DuckDB SQL type from a list of raw string values.
     *
     * @param samples Raw string values from a column (first N rows)
     * @return DuckDB SQL type string: "BOOLEAN", "BIGINT", "DOUBLE", "DATE", "VARCHAR"
     */
    public static String infer(List<String> samples) {
        List<String> meaningful = samples.stream()
                .filter(v -> v != null && !v.trim().isEmpty())
                .limit(SAMPLE_SIZE)
                .collect(java.util.stream.Collectors.toList());

        if (meaningful.isEmpty()) {
            return "VARCHAR"; // all nulls — default to VARCHAR
        }

        if (allMatch(meaningful, DataTypeInferrer::isBoolean)) return "BOOLEAN";
        if (allMatch(meaningful, DataTypeInferrer::isLong))    return "BIGINT";
        if (allMatch(meaningful, DataTypeInferrer::isDouble))  return "DOUBLE";
        if (allMatch(meaningful, DataTypeInferrer::isDate))    return "DATE";

        return "VARCHAR";
    }

    private static boolean allMatch(List<String> values, java.util.function.Predicate<String> test) {
        return values.stream().allMatch(test);
    }

    private static boolean isBoolean(String v) {
        String lower = v.toLowerCase().trim();
        return lower.equals("true") || lower.equals("false")
                || lower.equals("yes") || lower.equals("no")
                || lower.equals("1") || lower.equals("0");
    }

    private static boolean isLong(String v) {
        try {
            Long.parseLong(v.trim().replace(",", ""));
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private static boolean isDouble(String v) {
        try {
            Double.parseDouble(v.trim().replace(",", ""));
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private static boolean isDate(String v) {
        String trimmed = v.trim();
        for (DateTimeFormatter fmt : DATE_FORMATS) {
            try {
                LocalDate.parse(trimmed, fmt);
                return true;
            } catch (DateTimeParseException ignored) {
            }
        }
        return false;
    }
}