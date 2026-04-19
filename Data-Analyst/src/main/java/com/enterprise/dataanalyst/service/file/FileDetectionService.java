// service/file/FileDetectionService.java
package com.enterprise.dataanalyst.service.file;

import lombok.extern.slf4j.Slf4j;
import org.apache.tika.Tika;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;

/**
 * Detects the true MIME type of an uploaded file using Apache Tika.
 *
 * WHY TIKA INSTEAD OF FILE EXTENSION:
 * File extensions are user-controlled and untrustworthy.
 * A user could rename "malware.exe" to "data.csv".
 *
 * Tika reads the file's "magic bytes" — the first few bytes that every
 * file format defines as its signature. For example:
 *   - ZIP files start with PK\x03\x04 (xlsx is actually a ZIP)
 *   - PDF files start with %PDF
 *   - XLS files start with D0 CF 11 E0 (OLE2 compound document)
 *
 * This is content-based detection — much more reliable.
 */
@Service
@Slf4j
public class FileDetectionService {

    // Tika is thread-safe; create once and reuse
    private final Tika tika = new Tika();

    /**
     * Detect MIME type from file content.
     *
     * @return MIME type string e.g., "text/csv", "application/vnd.ms-excel"
     */
    public String detectMimeType(MultipartFile file) throws IOException {
        try (InputStream inputStream = file.getInputStream()) {
            String mimeType = tika.detect(inputStream, file.getOriginalFilename());
            log.debug("File '{}' detected as MIME type: {}", file.getOriginalFilename(), mimeType);
            return mimeType;
        }
    }

    public boolean isSupportedType(String mimeType) {
        return mimeType != null && (
                mimeType.equals("text/csv")
                || mimeType.equals("text/plain")                              // sometimes CSV is detected as plain text
                || mimeType.equals("application/vnd.ms-excel")                // .xls
                || mimeType.equals("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet") // .xlsx
                || mimeType.equals("application/x-tika-ooxml")               // Tika's generic OOXML
        );
    }
}