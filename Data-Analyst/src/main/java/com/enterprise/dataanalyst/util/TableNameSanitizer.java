// util/TableNameSanitizer.java
package com.enterprise.dataanalyst.util;

import org.apache.commons.lang3.StringUtils;

/**
 * Converts user-provided filenames into valid SQL identifiers for DuckDB.
 *
 * WHY THIS MATTERS:
 * "Q1 Sales Data.xlsx" cannot be a table name in SQL — spaces and dots are invalid.
 * More critically, a malicious filename like "employees; DROP TABLE users; --"
 * must be neutralized. We do this by whitelisting, not blacklisting.
 *
 * WHITELIST APPROACH:
 * Only keep [a-z0-9_]. Replace anything else with underscore.
 * Collapse multiple underscores. Ensure it starts with a letter.
 */
public class TableNameSanitizer {

    private static final int MAX_TABLE_NAME_LENGTH = 60;

    private TableNameSanitizer() {}

    public static String sanitize(String rawName) {
        if (StringUtils.isBlank(rawName)) {
            throw new IllegalArgumentException("Table name cannot be blank");
        }

        // Remove file extension
        String name = rawName;
        int lastDot = rawName.lastIndexOf('.');
        if (lastDot > 0) {
            name = rawName.substring(0, lastDot);
        }

        // Lowercase, replace all non-alphanumeric with underscore
        String sanitized = name.toLowerCase()
                .replaceAll("[^a-z0-9]", "_")   // whitelist: only letters, digits
                .replaceAll("_+", "_")           // collapse multiple underscores
                .replaceAll("^_+|_+$", "");      // trim leading/trailing underscores

        // Must start with a letter (SQL identifier rule)
        if (sanitized.isEmpty() || !Character.isLetter(sanitized.charAt(0))) {
            sanitized = "t_" + sanitized;
        }

        // Truncate
        if (sanitized.length() > MAX_TABLE_NAME_LENGTH) {
            sanitized = sanitized.substring(0, MAX_TABLE_NAME_LENGTH);
        }

        return sanitized;
    }

    /**
     * Same sanitization for column names.
     * Columns follow the same identifier rules as tables.
     */
    public static String sanitizeColumn(String rawColumnName) {
        return sanitize(rawColumnName.isBlank() ? "col" : rawColumnName);
    }
}