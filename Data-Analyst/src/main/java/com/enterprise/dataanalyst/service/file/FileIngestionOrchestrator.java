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
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * FileIngestionOrchestrator
 *
 * Coordinates the entire file upload pipeline.
 *
 * PIPELINE FOR CSV:
 * Detect MIME → CsvParser → Load DuckDB → Register
 *
 * PIPELINE FOR EXCEL:
 * Detect MIME → ExcelParser (ALL sheets) →
 * For each sheet → Load DuckDB → Register
 *
 * WHY MULTIPLE RESPONSES FOR EXCEL:
 * Each sheet becomes a separate DuckDB table.
 * We return one FileUploadResponse per sheet so
 * the UI can show each sheet as a separate loaded table.
 *
 * EXAMPLE:
 * employees.xlsx with sheets "Q1", "Q2", "Q3"
 * → tables: employees_q1, employees_q2, employees_q3
 * → 3 FileUploadResponse objects returned
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class FileIngestionOrchestrator {

    private final FileDetectionService detectionService;
    private final CsvParserService csvParser;
    private final ExcelParserService excelParser;
    private final DuckDBStorageService storageService;
    private final TableMetadataRegistry registry;

    /**
     * Main entry point — ingest uploaded file.
     * Returns list because Excel may have multiple sheets.
     * CSV always returns list with single element.
     */
    public List<FileUploadResponse> ingest(MultipartFile file) {
        String originalFileName = file.getOriginalFilename();
        log.info("Starting ingestion: {}", originalFileName);

        try {
            // Step 1: Detect MIME type from file content
            String mimeType = detectionService.detectMimeType(file);
            log.info("Detected MIME: {}", mimeType);

            if (!detectionService.isSupportedType(mimeType)) {
                throw new UnsupportedFileTypeException(
                    "File type '" + mimeType + "' is not supported. " +
                    "Supported: CSV, XLS, XLSX");
            }

            // Step 2: Route to correct parser
            if (isExcelType(mimeType)) {
                return ingestExcel(file);
            } else {
                return ingestCsv(file, mimeType);
            }

        } catch (UnsupportedFileTypeException e) {
            throw e; // rethrow as-is
        } catch (IOException e) {
            throw new FileProcessingException(
                "Failed to read file: " + originalFileName, e);
        } catch (SQLException e) {
            throw new FileProcessingException(
                "Failed to load into database: " + e.getMessage(), e);
        }
    }

    // ─────────────────────────────────────────────────────────────
    // CSV INGESTION
    // ─────────────────────────────────────────────────────────────

    private List<FileUploadResponse> ingestCsv(
            MultipartFile file, String mimeType)
            throws IOException, SQLException {

        String originalFileName = file.getOriginalFilename();
        String tableName = TableNameSanitizer.sanitize(originalFileName);

        log.info("Parsing CSV → table: '{}'", tableName);

        // Parse
        ParsedFileData parsedData = csvParser.parse(file, tableName);

        // Load into DuckDB
        storageService.loadTable(tableName, parsedData);

        // Register metadata
        TableMetadata metadata = buildMetadata(
            tableName, parsedData, null);
        registry.register(metadata);

        log.info("CSV ingestion complete: '{}' → '{}', {} rows",
                originalFileName, tableName,
                parsedData.getRows().size());

        // Return single-element list
        List<FileUploadResponse> responses = new ArrayList<>();
        responses.add(buildResponse(tableName, parsedData, null));
        return responses;
    }

    // ─────────────────────────────────────────────────────────────
    // EXCEL INGESTION — MULTI-SHEET
    // ─────────────────────────────────────────────────────────────

    private List<FileUploadResponse> ingestExcel(MultipartFile file)
            throws IOException, SQLException {

        String originalFileName = file.getOriginalFilename();
        String baseFileName = originalFileName
                .replaceAll("\\.[^.]+$", ""); // remove extension

        log.info("Parsing Excel (all sheets): {}", originalFileName);

        // Parse ALL sheets — returns one ParsedFileData per sheet
        List<ParsedFileData> sheets = excelParser.parseAllSheets(file);

        if (sheets.isEmpty()) {
            throw new FileProcessingException(
                "Excel file has no readable sheets: " + originalFileName);
        }

        List<FileUploadResponse> responses = new ArrayList<>();

        for (ParsedFileData sheetData : sheets) {
            // Table name = basefile_sheetname
            // e.g., employees_q1, employees_q2
            String sheetName = sheetData.getSheetName();
            String tableName = TableNameSanitizer.sanitize(
                baseFileName + "_" + sheetName);

            log.info("Loading sheet '{}' → table '{}'",
                    sheetName, tableName);

            // Load each sheet as separate DuckDB table
            storageService.loadTable(tableName, sheetData);

            // Register each sheet separately
            TableMetadata metadata = buildMetadata(
                tableName, sheetData, sheetName);
            registry.register(metadata);

            responses.add(buildResponse(
                tableName, sheetData, sheetName));

            log.info("Sheet '{}' loaded: {} rows, {} cols",
                    sheetName,
                    sheetData.getRows().size(),
                    sheetData.getColumns().size());
        }

        log.info("Excel ingestion complete: {} sheet(s) loaded",
                responses.size());
        return responses;
    }

    // ─────────────────────────────────────────────────────────────
    // HELPERS
    // ─────────────────────────────────────────────────────────────

    private boolean isExcelType(String mimeType) {
        return mimeType.contains("ms-excel") ||
               mimeType.contains("spreadsheetml") ||
               mimeType.contains("tika-ooxml");
    }

    private TableMetadata buildMetadata(String tableName,
                                         ParsedFileData data,
                                         String sheetName) {
        return TableMetadata.builder()
                .tableName(tableName)
                .originalFileName(data.getOriginalFileName())
                .fileType(data.getDetectedFileType())
                .rowCount(data.getRows().size())
                .columns(data.getColumns())
                .uploadedAt(LocalDateTime.now())
                .build();
    }

    private FileUploadResponse buildResponse(String tableName,
                                              ParsedFileData data,
                                              String sheetName) {
        String message = sheetName != null
            ? "Sheet '" + sheetName + "' loaded successfully."
            : "File loaded successfully.";

        return FileUploadResponse.builder()
                .tableName(tableName)
                .originalFileName(data.getOriginalFileName())
                .fileType(data.getDetectedFileType())
                .rowCount(data.getRows().size())
                .columns(data.getColumns().stream()
                    .map(c -> c.getColumnName() +
                              " (" + c.getDataType() + ")")
                    .collect(Collectors.toList()))
                .message(message)
                .build();
    }
}
