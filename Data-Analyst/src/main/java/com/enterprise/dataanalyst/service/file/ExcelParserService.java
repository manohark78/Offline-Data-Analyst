package com.enterprise.dataanalyst.service.file;

import com.enterprise.dataanalyst.model.ColumnMetadata;
import com.enterprise.dataanalyst.model.ParsedFileData;
import com.enterprise.dataanalyst.util.DataTypeInferrer;
import com.enterprise.dataanalyst.util.TableNameSanitizer;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.*;

/**
 * Parses ALL sheets from Excel files.
 *
 * CHANGE FROM BEFORE:
 * Before: only first sheet was parsed
 * Now: ALL sheets parsed, each becomes a separate table
 *
 * Table naming:
 * employees.xlsx with sheets "Q1", "Q2"
 * → tables: "employees_q1", "employees_q2"
 *
 * WHY SEPARATE TABLES PER SHEET:
 * DuckDB cannot have mixed schemas in one table.
 * Each sheet may have different columns.
 * Separate tables = correct schema per sheet.
 */
@Service
@Slf4j
public class ExcelParserService implements FileParserService {

    @Override
    public boolean supports(String mimeType) {
        return "application/vnd.ms-excel".equals(mimeType) ||
               "application/vnd.openxmlformats-officedocument" +
               ".spreadsheetml.sheet".equals(mimeType) ||
               "application/x-tika-ooxml".equals(mimeType);
    }

    /**
     * Returns multiple ParsedFileData — one per sheet.
     * FileIngestionOrchestrator calls parseAllSheets() now.
     */
    @Override
    public ParsedFileData parse(MultipartFile file,
                                String tableName) throws IOException {
        // Legacy — parse first sheet only
        // Used internally when single sheet needed
        List<ParsedFileData> all = parseAllSheets(file);
        return all.isEmpty() ? null : all.get(0);
    }

    /**
     * Parse ALL sheets — main method for multi-sheet support.
     */
    public List<ParsedFileData> parseAllSheets(
            MultipartFile file) throws IOException {

        log.info("Parsing Excel: {}", file.getOriginalFilename());

        String mimeType = detectSubtype(file.getOriginalFilename());
        Workbook workbook = openWorkbook(file, mimeType);
        String fileType = mimeType.contains("ms-excel")
                ? "XLS" : "XLSX";
        String baseFileName = file.getOriginalFilename()
                .replaceAll("\\.[^.]+$", "");

        List<ParsedFileData> results = new ArrayList<>();

        try {
            int sheetCount = workbook.getNumberOfSheets();
            log.info("Excel has {} sheet(s)", sheetCount);

            for (int i = 0; i < sheetCount; i++) {
                Sheet sheet = workbook.getSheetAt(i);
                String sheetName = sheet.getSheetName();

                log.info("Parsing sheet {}/{}: '{}'",
                        i + 1, sheetCount, sheetName);

                // Skip hidden or empty sheets
                if (workbook.isSheetHidden(i) ||
                    workbook.isSheetVeryHidden(i)) {
                    log.info("Skipping hidden sheet: '{}'",
                            sheetName);
                    continue;
                }

                if (isSheetEmpty(sheet)) {
                    log.info("Skipping empty sheet: '{}'",
                            sheetName);
                    continue;
                }

                // Table name = filename_sheetname
                // e.g., employees_q1_data
                String tableName = TableNameSanitizer.sanitize(
                    baseFileName + "_" + sheetName);

                ParsedFileData sheetData = parseSheet(
                    sheet, file.getOriginalFilename(),
                    fileType, tableName, sheetName);

                if (sheetData != null &&
                    !sheetData.getRows().isEmpty()) {
                    results.add(sheetData);
                    log.info(
                        "Sheet '{}' → table '{}': {} rows, {} cols",
                        sheetName, tableName,
                        sheetData.getRows().size(),
                        sheetData.getColumns().size());
                }
            }
        } finally {
            workbook.close();
        }

        log.info("Excel parsing complete: {} valid sheets",
                results.size());
        return results;
    }

    private ParsedFileData parseSheet(Sheet sheet,
            String originalFileName, String fileType,
            String tableName, String sheetName) {

        Iterator<Row> rowIterator = sheet.iterator();
        if (!rowIterator.hasNext()) return null;

        // First row = headers
        Row headerRow = rowIterator.next();
        List<String> originalHeaders = extractHeaders(headerRow);
        if (originalHeaders.isEmpty()) return null;

        List<String> sanitizedHeaders = new ArrayList<>();
        for (String h : originalHeaders) {
            sanitizedHeaders.add(
                TableNameSanitizer.sanitizeColumn(h));
        }

        DataFormatter formatter = new DataFormatter();
        List<Map<String, String>> rows = new ArrayList<>();

        while (rowIterator.hasNext()) {
            Row row = rowIterator.next();
            if (isRowEmpty(row)) continue;

            Map<String, String> rowData = new LinkedHashMap<>();
            for (int i = 0; i < sanitizedHeaders.size(); i++) {
                Cell cell = row.getCell(i,
                    Row.MissingCellPolicy.RETURN_BLANK_AS_NULL);
                String value = extractCellValue(cell, formatter);
                rowData.put(sanitizedHeaders.get(i),
                    (value == null || value.trim().isEmpty())
                        ? null : value.trim());
            }
            rows.add(rowData);
        }

        List<ColumnMetadata> columns = buildColumnMetadata(
            originalHeaders, sanitizedHeaders, rows, sheetName);

        return ParsedFileData.builder()
                .columns(columns)
                .rows(rows)
                .originalFileName(originalFileName +
                    " [Sheet: " + sheetName + "]")
                .detectedFileType(fileType)
                .sheetName(sheetName)  // add this field
                .build();
    }

    private boolean isSheetEmpty(Sheet sheet) {
        if (sheet.getLastRowNum() <= 0) return true;
        Row firstRow = sheet.getRow(sheet.getFirstRowNum());
        return firstRow == null || firstRow.getLastCellNum() <= 0;
    }

    private List<String> extractHeaders(Row headerRow) {
        List<String> headers = new ArrayList<>();
        for (Cell cell : headerRow) {
            String header = "";
            if (cell.getCellType() == CellType.STRING) {
                header = cell.getStringCellValue().trim();
            }
            headers.add(header.isEmpty()
                ? "col_" + cell.getColumnIndex() : header);
        }
        return headers;
    }

    private String extractCellValue(Cell cell,
                                     DataFormatter formatter) {
        if (cell == null) return null;
        switch (cell.getCellType()) {
            case NUMERIC:
                if (DateUtil.isCellDateFormatted(cell)) {
                    return cell.getLocalDateTimeCellValue()
                               .toLocalDate().toString();
                }
                return formatter.formatCellValue(cell);
            case STRING:
                return cell.getStringCellValue();
            case BOOLEAN:
                return String.valueOf(cell.getBooleanCellValue());
            case FORMULA:
                try { return formatter.formatCellValue(cell); }
                catch (Exception e) {
                    return cell.getCellFormula(); }
            default: return null;
        }
    }

    private boolean isRowEmpty(Row row) {
        if (row == null) return true;
        for (Cell cell : row) {
            if (cell != null &&
                cell.getCellType() != CellType.BLANK)
                return false;
        }
        return true;
    }

    private List<ColumnMetadata> buildColumnMetadata(
            List<String> originalHeaders,
            List<String> sanitizedHeaders,
            List<Map<String, String>> rows,
            String sheetName) {

        List<ColumnMetadata> columns = new ArrayList<>();
        for (int i = 0; i < sanitizedHeaders.size(); i++) {
            String sanitized = sanitizedHeaders.get(i);
            List<String> samples = new ArrayList<>();
            for (Map<String, String> row : rows) {
                String v = row.get(sanitized);
                if (v != null) samples.add(v);
                if (samples.size() >= 200) break;
            }
            columns.add(ColumnMetadata.builder()
                    .columnName(sanitized)
                    .originalName(originalHeaders.get(i))
                    .dataType(DataTypeInferrer.infer(samples))
                    .ordinalPosition(i)
                    .build());
        }
        return columns;
    }

    private Workbook openWorkbook(MultipartFile file,
                                   String mimeType)
            throws IOException {
        if ("application/vnd.ms-excel".equals(mimeType)) {
            return new HSSFWorkbook(file.getInputStream());
        }
        return new XSSFWorkbook(file.getInputStream());
    }

    private String detectSubtype(String filename) {
        if (filename != null &&
            filename.toLowerCase().endsWith(".xls")) {
            return "application/vnd.ms-excel";
        }
        return "application/vnd.openxmlformats-" +
               "officedocument.spreadsheetml.sheet";
    }
            }
