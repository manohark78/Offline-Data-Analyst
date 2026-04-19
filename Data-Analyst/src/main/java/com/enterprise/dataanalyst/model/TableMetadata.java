// model/TableMetadata.java
package com.enterprise.dataanalyst.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Represents a fully loaded table in DuckDB.
 * Created once per uploaded file.
 *
 * WHY WE STORE THIS:
 * When a user asks "which files have an email column", we need to
 * search across ALL uploaded file schemas without querying DuckDB.
 * TableMetadataRegistry holds these in memory for fast lookup.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TableMetadata {
    private String tableName;         // DuckDB table name (sanitized)
    private String originalFileName;  // as uploaded by user
    private String fileType;          // CSV, XLS, XLSX
    private long rowCount;
    private List<ColumnMetadata> columns;
    private LocalDateTime uploadedAt;

    /**
     * Check if this table contains a column matching the given name.
     * Case-insensitive, partial match supported.
     */
    public boolean hasColumn(String columnNameQuery) {
        String q = columnNameQuery.toLowerCase().trim();
        return columns.stream()
                .anyMatch(c -> c.getColumnName().toLowerCase().contains(q)
                        || c.getOriginalName().toLowerCase().contains(q));
    }

    /**
     * Find the best matching column name from this table.
     * Returns the sanitized DuckDB column name for use in SQL.
     */
    public String resolveColumn(String userColumnQuery) {
        String q = userColumnQuery.toLowerCase().trim();
        return columns.stream()
                .filter(c -> c.getColumnName().toLowerCase().contains(q)
                        || c.getOriginalName().toLowerCase().contains(q))
                .map(ColumnMetadata::getColumnName)
                .findFirst()
                .orElse(null);
    }
}