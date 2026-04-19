// service/file/CsvParserService.java
package com.enterprise.dataanalyst.service.file;

import com.enterprise.dataanalyst.model.ColumnMetadata;
import com.enterprise.dataanalyst.model.ParsedFileData;
import com.enterprise.dataanalyst.util.DataTypeInferrer;
import com.enterprise.dataanalyst.util.TableNameSanitizer;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Parses CSV files using Apache Commons CSV.
 *
 * DESIGN DECISIONS:
 *
 * 1. BOM Handling:
 *    Excel-exported CSVs often start with a UTF-8 BOM (EF BB BF).
 *    Commons CSV handles this with withFirstRecordAsHeader() + StandardCharsets.UTF_8.
 *    We read with explicit UTF-8 charset.
 *
 * 2. Two-pass parsing:
 *    Pass 1: Read ALL records to collect samples for type inference.
 *    This means the entire file is in memory as strings briefly.
 *    For very large files (>100MB), a streaming approach would be better — noted as future work.
 *
 * 3. Null handling:
 *    Empty strings in CSV represent nulls. We normalize them to null
 *    so DuckDB treats them correctly.
 */
@Service
@Slf4j
public class CsvParserService implements FileParserService {

    @Override
    public boolean supports(String mimeType) {
        return "text/csv".equals(mimeType) || "text/plain".equals(mimeType);
    }

    @Override
    public ParsedFileData parse(MultipartFile file, String tableName) throws IOException {
        log.info("Parsing CSV file: {}", file.getOriginalFilename());

        // WHY InputStreamReader with explicit charset:
        // Default charset is platform-dependent. Enterprise files from Windows
        // may be UTF-8 with BOM. Being explicit prevents mojibake.
        Reader reader = new InputStreamReader(file.getInputStream(), StandardCharsets.UTF_8);

        CSVFormat format = CSVFormat.Builder.create(CSVFormat.DEFAULT)
                .setHeader()                  // first row = header
                .setSkipHeaderRecord(true)    // don't include header in records
                .setIgnoreEmptyLines(true)    // skip blank lines
                .setTrim(true)               // trim whitespace from values
                .setIgnoreSurroundingSpaces(true)
                .build();

        List<Map<String, String>> rows = new ArrayList<>();
        List<String> headers;

        try (CSVParser csvParser = new CSVParser(reader, format)) {
            headers = new ArrayList<>(csvParser.getHeaderNames());
            log.debug("CSV headers detected: {}", headers);

            for (CSVRecord record : csvParser) {
                Map<String, String> row = new LinkedHashMap<>();
                for (String header : headers) {
                    String value = record.get(header);
                    // Normalize empty string to null — represents missing data
                    row.put(TableNameSanitizer.sanitizeColumn(header),
                            (value == null || value.trim().isEmpty()) ? null : value.trim());
                }
                rows.add(row);
            }
        }

        log.info("CSV parsed: {} columns, {} rows", headers.size(), rows.size());

        // Build column metadata with inferred types
        List<ColumnMetadata> columns = buildColumnMetadata(headers, rows);

        return ParsedFileData.builder()
                .columns(columns)
                .rows(rows)
                .originalFileName(file.getOriginalFilename())
                .detectedFileType("CSV")
                .build();
    }

    private List<ColumnMetadata> buildColumnMetadata(List<String> headers, List<Map<String, String>> rows) {
        List<ColumnMetadata> columns = new ArrayList<>();
        int ordinal = 0;

        for (String header : headers) {
            String sanitizedName = TableNameSanitizer.sanitizeColumn(header);

            // Collect sample values for this column
            List<String> samples = new ArrayList<>();
            for (Map<String, String> row : rows) {
                String val = row.get(sanitizedName);
                if (val != null) samples.add(val);
                if (samples.size() >= 200) break; // sample limit
            }

            String inferredType = DataTypeInferrer.infer(samples);
            log.debug("Column '{}' ({}) → inferred type: {}", header, sanitizedName, inferredType);

            columns.add(ColumnMetadata.builder()
                    .columnName(sanitizedName)
                    .originalName(header)
                    .dataType(inferredType)
                    .ordinalPosition(ordinal++)
                    .build());
        }

        return columns;
    }
}