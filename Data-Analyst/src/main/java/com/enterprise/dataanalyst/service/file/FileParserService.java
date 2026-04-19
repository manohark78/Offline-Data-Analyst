// service/file/FileParserService.java
package com.enterprise.dataanalyst.service.file;

import com.enterprise.dataanalyst.model.ParsedFileData;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;

/**
 * Contract for all file format parsers.
 *
 * WHY AN INTERFACE:
 * Open/Closed Principle. Adding PDF or JSON support in the future
 * means creating a new implementation — not modifying existing code.
 * FileIngestionOrchestrator selects the correct parser at runtime
 * based on detected MIME type.
 */
public interface FileParserService {

    /**
     * Parse the uploaded file and return a structured intermediate representation.
     *
     * @param file     The uploaded file
     * @param tableName The sanitized table name (for column name tracking)
     * @return ParsedFileData containing columns with inferred types + all rows as strings
     * @throws IOException if file cannot be read
     */
    ParsedFileData parse(MultipartFile file, String tableName) throws IOException;

    /**
     * Returns the MIME types this parser handles.
     * Used by orchestrator for parser selection.
     */
    boolean supports(String mimeType);
}