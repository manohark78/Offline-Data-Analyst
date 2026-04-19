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
}