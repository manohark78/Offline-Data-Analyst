// service/file/ExcelParserService.java
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
 * Parses .xls and .xlsx files using Apache POI.
 *
 * ARCHITECTURE NOTE — XLS vs XLSX:
 * .xls  → Binary format (BIFF8). POI uses HSSFWorkbook.
 * .xlsx → XML-based (OOXML). POI uses XSSFWorkbook.
 * Both implement the Workbook interface → we handle them uniformly.
 *
 * SHEET SELECTION:
 * We parse the FIRST sheet by default.
 * Future: Accept sheet name as parameter for multi-sheet files.
 *
 * CELL TYPE HANDLING:
 * Excel cells have their own type system (NUMERIC, STRING, BOOLEAN, FORMULA).
 * We normalize everything to strings here. Type inference happens in DataTypeInferrer.
 *
 * WHY NOT DuckDB'S read_xlsx:
 * DuckDB has an Excel extension but it's not bundled in the standard JDBC driver.
 * Apache POI is more reliable and gives us full programmatic control.
 */
@Service
@Slf4j
public class ExcelParserService implements FileParserService {

    @Override
    public boolean supports(String mimeType) {
        return "application/vnd.ms-excel".equals(mimeType)
                || "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet".equals(mimeType)
                || "application/x-tika-ooxml".equals(mimeType);
    }

    @Override
    public ParsedFileData parse(MultipartFile file, String tableName) throws IOException {
        log.info("Parsing Excel file: {}", file.getOriginalFilename());

        String mimeType = detectExcelSubtype(file.getOriginalFilename());
        Workbook workbook = openWorkbook(file, mimeType);

        try {
            Sheet sheet = workbook.getSheetAt(0); // first sheet
            log.info("Parsing sheet: '{}'", sheet.getSheetName());

            return parseSheet(sheet, file.getOriginalFilename(), mimeType);
        } finally {
            workbook.close();
        }
    }

    private ParsedFileData parseSheet(Sheet sheet, String originalFileName, String fileType) {
        Iterator<Row> rowIterator = sheet.iterator();

        // First row = headers
        if (!rowIterator.hasNext()) {
            throw new IllegalArgumentException("Excel file is empty: " + originalFileName);
        }

        Row headerRow = rowIterator.next();
        List<String> originalHeaders = extractHeaders(headerRow);
        List<String> sanitizedHeaders = new ArrayList<>();
        for (String h : originalHeaders) {
            sanitizedHeaders.add(TableNameSanitizer.sanitizeColumn(h));
        }

        log.debug("Excel headers: {}", originalHeaders);

        // Parse data rows
        List<Map<String, String>> rows = new ArrayList<>();
        DataFormatter formatter = new DataFormatter(); // formats numeric cells as their displayed value

        while (rowIterator.hasNext()) {
            Row row = rowIterator.next();

            // Skip completely empty rows
            if (isRowEmpty(row)) continue;

            Map<String, String> rowData = new LinkedHashMap<>();
            for (int i = 0; i < sanitizedHeaders.size(); i++) {
                Cell cell = row.getCell(i, Row.MissingCellPolicy.RETURN_BLANK_AS_NULL);
                String value = extractCellValue(cell, formatter);
                rowData.put(sanitizedHeaders.get(i),
                        (value == null || value.trim().isEmpty()) ? null : value.trim());
            }
            rows.add(rowData);
        }

        log.info("Excel parsed: {} columns, {} rows", originalHeaders.size(), rows.size());

        // Build column metadata
        List<ColumnMetadata> columns = buildColumnMetadata(originalHeaders, sanitizedHeaders, rows);

        return ParsedFileData.builder()
                .columns(columns)
                .rows(rows)
                .originalFileName(originalFileName)
                .detectedFileType(fileType.contains("xls") ? "XLS" : "XLSX")
                .build();
    }

    private List<String> extractHeaders(Row headerRow) {
        List<String> headers = new ArrayList<>();
        for (Cell cell : headerRow) {
            String header = cell.getStringCellValue().trim();
            headers.add(header.isEmpty() ? "col_" + cell.getColumnIndex() : header);
        }
        return headers;
    }

    /**
     * Extract cell value as string regardless of Excel cell type.
     *
     * WHY THIS IS NECESSARY:
     * Excel has its own type system. A "salary" column may have NUMERIC cells
     * but we want "50000" not 50000.0. DataFormatter applies Excel's own
     * number format rules, giving us what the user sees in Excel.
     */
    private String extractCellValue(Cell cell, DataFormatter formatter) {
        if (cell == null) return null;

        switch (cell.getCellType()) {
            case NUMERIC:
                if (DateUtil.isCellDateFormatted(cell)) {
                    // Date cell — return ISO format
                    return cell.getLocalDateTimeCellValue().toLocalDate().toString();
                }
                return formatter.formatCellValue(cell);
            case STRING:
                return cell.getStringCellValue();
            case BOOLEAN:
                return String.valueOf(cell.getBooleanCellValue());
            case FORMULA:
                // Evaluate formula and return cached value
                try {
                    return formatter.formatCellValue(cell);
                } catch (Exception e) {
                    return cell.getCellFormula();
                }
            case BLANK:
            case _NONE:
            default:
                return null;
        }
    }

    private boolean isRowEmpty(Row row) {
        if (row == null) return true;
        for (Cell cell : row) {
            if (cell != null && cell.getCellType() != CellType.BLANK) return false;
        }
        return true;
    }

    private List<ColumnMetadata> buildColumnMetadata(List<String> originalHeaders,
                                                      List<String> sanitizedHeaders,
                                                      List<Map<String, String>> rows) {
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

    private Workbook openWorkbook(MultipartFile file, String mimeType) throws IOException {
        if ("application/vnd.ms-excel".equals(mimeType)) {
            return new HSSFWorkbook(file.getInputStream()); // .xls
        }
        return new XSSFWorkbook(file.getInputStream()); // .xlsx
    }

    private String detectExcelSubtype(String filename) {
        if (filename != null && filename.toLowerCase().endsWith(".xls")) {
            return "application/vnd.ms-excel";
        }
        return "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
    }
}