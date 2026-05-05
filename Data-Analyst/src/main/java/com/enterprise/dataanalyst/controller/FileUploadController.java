// controller/FileUploadController.java
package com.enterprise.dataanalyst.controller;

import com.enterprise.dataanalyst.dto.FileUploadResponse;
import com.enterprise.dataanalyst.dto.SessionDTO;
import com.enterprise.dataanalyst.model.TableMetadata;
import com.enterprise.dataanalyst.service.file.FileIngestionOrchestrator;
import com.enterprise.dataanalyst.service.registry.TableMetadataRegistry;
import com.enterprise.dataanalyst.service.session.SessionService;
import com.enterprise.dataanalyst.service.storage.DuckDBStorageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.Collection;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/files")
@RequiredArgsConstructor
@Slf4j
public class FileUploadController {

    private final FileIngestionOrchestrator orchestrator;
    private final TableMetadataRegistry registry;
    private final SessionService sessionService;
    private final DuckDBStorageService storageService;

    @PostMapping("/upload")
    public ResponseEntity<List<FileUploadResponse>> upload(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "sessionId", required = false) String sessionId) {

        if (file.isEmpty()) {
            FileUploadResponse err = FileUploadResponse.builder()
                    .message("File is empty.")
                    .build();
            return ResponseEntity.badRequest()
                    .body(List.of(err));
        }

        // Returns list — one per sheet for Excel,
        // single element for CSV
        List<FileUploadResponse> responses =
                orchestrator.ingest(file);

        // Link uploaded tables to the session so they show in the UI
        if (sessionId != null) {
            for (FileUploadResponse resp : responses) {
                try {
                    sessionService.addTableToSession(
                            sessionId, resp.getTableName());
                } catch (Exception e) {
                    log.warn("Failed to link table '{}' to session '{}': {}",
                            resp.getTableName(), sessionId, e.getMessage());
                }
            }
        }

        return ResponseEntity.ok(responses);
    }

    @GetMapping
    public ResponseEntity<Collection<TableMetadata>> listUploadedFiles() {
        return ResponseEntity.ok(registry.getAllTables());
    }

    @DeleteMapping("/{tableName}")
    public ResponseEntity<Map<String, String>> deleteTable(
            @PathVariable String tableName,
            @RequestParam(value = "sessionId",
                          required = false) String sessionId) {
        try {
            if (registry.findByTableName(tableName).isEmpty()) {
                return ResponseEntity.notFound().build();
            }

            storageService.dropTable(tableName);
            registry.remove(tableName);

            if (sessionId != null) {
                sessionService.removeTableFromSession(
                    sessionId, tableName);
            }

            return ResponseEntity.ok(Map.of(
                "message", "'" + tableName + "' deleted."));
        } catch (Exception e) {
            return ResponseEntity.internalServerError()
                    .body(Map.of("error", e.getMessage()));
        }
}

    @GetMapping("/session/{sessionId}")
    public ResponseEntity<List<SessionDTO.TableInfo>> getSessionFiles(
            @PathVariable String sessionId) {
        try {
            SessionDTO detail =
                sessionService.getSessionDetail(sessionId);
            if (detail == null)
                return ResponseEntity.ok(List.of());
            return ResponseEntity.ok(detail.getTables());
        } catch (Exception e) {
            return ResponseEntity.internalServerError().build();
        }
    }
}
