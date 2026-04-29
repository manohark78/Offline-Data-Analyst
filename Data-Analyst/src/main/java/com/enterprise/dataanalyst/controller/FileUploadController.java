// controller/FileUploadController.java
package com.enterprise.dataanalyst.controller;

import com.enterprise.dataanalyst.dto.FileUploadResponse;
import com.enterprise.dataanalyst.model.TableMetadata;
import com.enterprise.dataanalyst.service.file.FileIngestionOrchestrator;
import com.enterprise.dataanalyst.service.registry.TableMetadataRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.Collection;

@RestController
@RequestMapping("/api/files")
@RequiredArgsConstructor
@Slf4j
public class FileUploadController {

    private final FileIngestionOrchestrator orchestrator;
    private final TableMetadataRegistry registry;

    @PostMapping("/upload")
    public ResponseEntity<FileUploadResponse> upload(
            @RequestParam("file") MultipartFile file) {

        if (file.isEmpty()) {
            return ResponseEntity.badRequest()
                    .body(FileUploadResponse.builder()
                            .message("File is empty. Please select a valid file.")
                            .build());
        }

        FileUploadResponse response = orchestrator.ingest(file);
        return ResponseEntity.ok(response);
    }

    @GetMapping
    public ResponseEntity<Collection<TableMetadata>> listUploadedFiles() {
        return ResponseEntity.ok(registry.getAllTables());
    }
    @PostMapping("/upload")
public ResponseEntity<List<FileUploadResponse>> upload(
        @RequestParam("file") MultipartFile file,
        @RequestParam(value = "sessionId",
                      required = false) String sessionId) {

    if (file.isEmpty()) {
        return ResponseEntity.badRequest()
                .body(List.of(FileUploadResponse.builder()
                        .message("File is empty.").build()));
    }

    List<FileUploadResponse> responses = orchestrator.ingest(file);

    // Associate each table with session
    if (sessionId != null && !sessionId.isBlank()) {
        responses.forEach(r -> {
            try {
                sessionService.addTableToSession(
                    sessionId, r.getTableName());
            } catch (Exception e) {
                log.warn("Could not associate table with session: {}",
                        e.getMessage());
            }
        });
    }

    return ResponseEntity.ok(responses);
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
