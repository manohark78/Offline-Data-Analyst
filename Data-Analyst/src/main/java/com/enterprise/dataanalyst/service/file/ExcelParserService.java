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
        return Set.of(
                "application/vnd.ms-excel",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                "application/x-tika-ooxml"
        ).contains(mimeType);
    }

    @Override
    public ParsedFileData parse(MultipartFile file, String tableName) throws IOException {
        List<ParsedFileData> all = parseAllSheets(file);
        return all.isEmpty() ? null : all.get(0);
    }

    public List<ParsedFileData> parseAllSheets(MultipartFile file) throws IOException {

        String originalFileName = Optional.ofNullable(file.getOriginalFilename())
                .orElse("unknown_file");

        log.info("Parsing Excel: {}", originalFileName);

        String mimeType = detectSubtype(originalFileName);
        String baseFileName = stripExtension(originalFileName);
        String fileType = mimeType.contains("ms-excel") ? "XLS" : "XLSX";

        List<ParsedFileData> results = new ArrayList<>();

        try (Workbook workbook = openWorkbook(file, mimeType)) {

            int sheetCount = workbook.getNumberOfSheets();
            log.info("Excel has {} sheet(s)", sheetCount);

            for (int i = 0; i < sheetCount; i++) {

                Sheet sheet = workbook.getSheetAt(i);
                String sheetName = sheet.getSheetName();

                if (shouldSkipSheet(workbook, sheet, i)) {
                    log.info("Skipping sheet: '{}'", sheetName);
                    continue;
                }

                String tableName = TableNameSanitizer.sanitize(
                        baseFileName + "_" + sheetName
                );

                ParsedFileData parsed = parseSheet(
                        sheet, originalFileName, fileType, tableName, sheetName
                );

                if (isValid(parsed)) {
                    results.add(parsed);

                    log.info(
                            "Sheet '{}' → table '{}': {} rows, {} cols",
                            sheetName, tableName,
                            parsed.getRows().size(),
                            parsed.getColumns().size()
                    );
                }
            }
        }

        log.info("Excel parsing complete: {} valid sheets", results.size());
        return results;
    }

    // ===================== CORE PARSING =====================

    private ParsedFileData parseSheet(
            Sheet sheet,
            String originalFileName,
            String fileType,
            String tableName,
            String sheetName
    ) {

        Iterator<Row> rowIterator = sheet.iterator();
        if (!rowIterator.hasNext()) return null;

        Row headerRow = rowIterator.next();
        List<String> originalHeaders = extractHeaders(headerRow);
        if (originalHeaders.isEmpty()) return null;

        List<String> sanitizedHeaders = originalHeaders.stream()
                .map(TableNameSanitizer::sanitizeColumn)
                .toList();

        DataFormatter formatter = new DataFormatter();
        List<Map<String, String>> rows = new ArrayList<>();

        while (rowIterator.hasNext()) {
            Row row = rowIterator.next();
            if (isRowEmpty(row)) continue;

            Map<String, String> rowData = new LinkedHashMap<>();

            for (int i = 0; i < sanitizedHeaders.size(); i++) {
                Cell cell = row.getCell(i, Row.MissingCellPolicy.RETURN_BLANK_AS_NULL);
                String value = extractCellValue(cell, formatter);

                rowData.put(sanitizedHeaders.get(i),
                        (value == null || value.isBlank()) ? null : value.trim());
            }

            rows.add(rowData);
        }

        List<ColumnMetadata> columns = buildColumnMetadata(
                originalHeaders, sanitizedHeaders, rows
        );

        return ParsedFileData.builder()
                .columns(columns)
                .rows(rows)
                .originalFileName(originalFileName + " [Sheet: " + sheetName + "]")
                .detectedFileType(fileType)
                .sheetName(sheetName)
                .build();
    }

    // ===================== HELPERS =====================

    private boolean shouldSkipSheet(Workbook workbook, Sheet sheet, int index) {
        return workbook.isSheetHidden(index)
               || workbook.isSheetVeryHidden(index)
               || isSheetEmpty(sheet);
    }

    private boolean isValid(ParsedFileData data) {
        return data != null && !data.getRows().isEmpty();
    }

    private String stripExtension(String filename) {
        return filename.replaceAll("\\.[^.]+$", "");
    }

    private List<String> extractHeaders(Row headerRow) {
        List<String> headers = new ArrayList<>();

        for (Cell cell : headerRow) {
            String header = (cell.getCellType() == CellType.STRING)
                    ? cell.getStringCellValue().trim()
                    : "";

            headers.add(header.isEmpty()
                    ? "col_" + cell.getColumnIndex()
                    : header);
        }
        return headers;
    }

    private String extractCellValue(Cell cell, DataFormatter formatter) {
        if (cell == null) return null;

        return switch (cell.getCellType()) {
            case NUMERIC -> DateUtil.isCellDateFormatted(cell)
                    ? cell.getLocalDateTimeCellValue().toLocalDate().toString()
                    : formatter.formatCellValue(cell);

            case STRING -> cell.getStringCellValue();

            case BOOLEAN -> String.valueOf(cell.getBooleanCellValue());

            case FORMULA -> {
                try {
                    yield formatter.formatCellValue(cell);
                } catch (Exception e) {
                    yield cell.getCellFormula();
                }
            }

            default -> null;
        };
    }

    private boolean isRowEmpty(Row row) {
        if (row == null) return true;

        for (Cell cell : row) {
            if (cell != null && cell.getCellType() != CellType.BLANK) {
                return false;
            }
        }
        return true;
    }

    private boolean isSheetEmpty(Sheet sheet) {
        if (sheet.getLastRowNum() <= 0) return true;

        Row firstRow = sheet.getRow(sheet.getFirstRowNum());
        return firstRow == null || firstRow.getLastCellNum() <= 0;
    }

    private List<ColumnMetadata> buildColumnMetadata(
            List<String> originalHeaders,
            List<String> sanitizedHeaders,
            List<Map<String, String>> rows
    ) {

        List<ColumnMetadata> columns = new ArrayList<>();

        for (int i = 0; i < sanitizedHeaders.size(); i++) {

            String column = sanitizedHeaders.get(i);

            List<String> samples = rows.stream()
                    .map(r -> r.get(column))
                    .filter(Objects::nonNull)
                    .limit(200)
                    .toList();

            columns.add(ColumnMetadata.builder()
                    .columnName(column)
                    .originalName(originalHeaders.get(i))
                    .dataType(DataTypeInferrer.infer(samples))
                    .ordinalPosition(i)
                    .build());
        }

        return columns;
    }

    private Workbook openWorkbook(MultipartFile file, String mimeType) throws IOException {
        return "application/vnd.ms-excel".equals(mimeType)
                ? new HSSFWorkbook(file.getInputStream())
                : new XSSFWorkbook(file.getInputStream());
    }

    private String detectSubtype(String filename) {
        if (filename != null && filename.toLowerCase().endsWith(".xls")) {
            return "application/vnd.ms-excel";
        }
        return "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
    }
}