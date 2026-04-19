// dto/FileUploadResponse.java
package com.enterprise.dataanalyst.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.util.List;

@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class FileUploadResponse {
    private String tableName;
    private String originalFileName;
    private String fileType;
    private long rowCount;
    private List<String> columns;
    private String message;
}