// service/file/FileIngestionOrchestrator.java
package com.enterprise.dataanalyst.service.file;

import com.enterprise.dataanalyst.dto.FileUploadResponse;
import com.enterprise.dataanalyst.exception.FileProcessingException;
import com.enterprise.dataanalyst.exception.UnsupportedFileTypeException;
import com.enterprise.dataanalyst.model.ParsedFileData;
import com.enterprise.dataanalyst.model.TableMetadata;
import com.enterprise.dataanalyst.service.registry.TableMetadataRegistry;
import com.enterprise.dataanalyst.service.storage.DuckDBStorageService;
import com.enterprise.dataanalyst.util.TableNameSanitizer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Coordinates the entire file upload pipeline.
 *
 * PIPELINE:
 * Detect MIME → Select Parser → Parse → Load to DuckDB → Register metadata
 *
 * WHY AN ORCHESTRATOR PATTERN:
 * Each step is in its own service. The orchestrator just calls them in order.
 * This makes each step independently testable and individually replaceable.
 * Future: Add a virus scan step between detection and parsing with zero impact.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class FileIngestionOrchestrator {

    private final FileDetectionService detectionService;
    private final List<FileParserService> parsers; // Spring auto-injects all FileParserService beans
    private final DuckDBStorageService storageService;
    private final TableMetadataRegistry registry;

    public FileUploadResponse ingest(MultipartFile file) {
        String originalFileName = file.getOriginalFilename();
        log.info("Starting ingestion for file: {}", originalFileName);

        try {
            // Step 1: Detect MIME type from content
            String mimeType = detectionService.detectMimeType(file);
            if (!detectionService.isSupportedType(mimeType)) {
                throw new UnsupportedFileTypeException(
                        "File type '" + mimeType + "' is not supported. " +
                        "Supported: CSV, XLS, XLSX");
            }

            // Step 2: Select the correct parser
            FileParserService parser = parsers.stream()
                    .filter(p -> p.supports(mimeType))
                    .findFirst()
                    .orElseThrow(() -> new UnsupportedFileTypeException(
                            "No parser available for MIME type: " + mimeType));

            // Step 3: Compute sanitized table name
            String tableName = TableNameSanitizer.sanitize(originalFileName);
            log.info("Table name for '{}': '{}'", originalFileName, tableName);

            // Step 4: Parse the file
            ParsedFileData parsedData = parser.parse(file, tableName);

            // Step 5: Load into DuckDB
            storageService.loadTable(tableName, parsedData);

            // Step 6: Register metadata for intent resolution
            TableMetadata metadata = TableMetadata.builder()
                    .tableName(tableName)
                    .originalFileName(originalFileName)
                    .fileType(parsedData.getDetectedFileType())
                    .rowCount(parsedData.getRows().size())
                    .columns(parsedData.getColumns())
                    .uploadedAt(LocalDateTime.now())
                    .build();
            registry.register(metadata);

            log.info("Ingestion complete: '{}' → table '{}', {} rows, {} columns",
                    originalFileName, tableName, parsedData.getRows().size(),
                    parsedData.getColumns().size());

            return FileUploadResponse.builder()
                    .tableName(tableName)
                    .originalFileName(originalFileName)
                    .fileType(parsedData.getDetectedFileType())
                    .rowCount(parsedData.getRows().size())
                    .columns(parsedData.getColumns().stream()
                            .map(c -> c.getColumnName() + " (" + c.getDataType() + ")")
                            .collect(Collectors.toList()))
                    .message("File uploaded and ready to query.")
                    .build();

        } catch (IOException e) {
            throw new FileProcessingException("Failed to read file: " + originalFileName, e);
        } catch (SQLException e) {
            throw new FileProcessingException("Failed to load data into database: " + e.getMessage(), e);
        }
    }
}