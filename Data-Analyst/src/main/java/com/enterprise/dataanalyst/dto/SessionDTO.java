package com.enterprise.dataanalyst.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.util.List;

@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class SessionDTO {
    private String sessionId;
    private String sessionName;
    private int tableCount;
    private int queryCount;
    private String createdAt;
    private String lastActive;
    private List<TableInfo> tables;

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class TableInfo {
        private String tableName;
        private String originalFile;
        private String fileType;
        private long rowCount;
        private String uploadedAt;
    }
}
