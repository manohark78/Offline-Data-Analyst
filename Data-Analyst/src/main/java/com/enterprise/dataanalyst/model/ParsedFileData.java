// model/ParsedFileData.java
package com.enterprise.dataanalyst.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

/**
 * Intermediate representation of a parsed file.
 *
 * WHY A MAP PER ROW:
 * At parse time, we don't yet know the final SQL types. Using
 * Map<String, String> keeps everything as strings until DuckDBStorageService
 * casts values to the correct type during INSERT. This decouples
 * parsing from type inference.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ParsedFileData {
    private List<ColumnMetadata> columns;
    // Each Map = one row. Key = sanitized column name, Value = raw string value
    private List<Map<String, String>> rows;
    private String originalFileName;
    private String detectedFileType;
    private String sheetName;
}