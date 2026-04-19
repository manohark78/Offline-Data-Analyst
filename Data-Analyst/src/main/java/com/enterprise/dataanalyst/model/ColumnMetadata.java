// model/ColumnMetadata.java
package com.enterprise.dataanalyst.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Represents a single column in an uploaded file/table.
 * Immutable after construction — schema doesn't change after file is loaded.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ColumnMetadata {
    private String columnName;       // sanitized name used in DuckDB
    private String originalName;     // original name from file header
    private String dataType;         // DuckDB SQL type: VARCHAR, BIGINT, DOUBLE, DATE, BOOLEAN
    private int ordinalPosition;     // 0-based column order
}