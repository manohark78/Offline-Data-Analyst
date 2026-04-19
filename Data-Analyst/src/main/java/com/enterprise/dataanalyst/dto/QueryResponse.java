// dto/QueryResponse.java
package com.enterprise.dataanalyst.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.util.List;

@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class QueryResponse {
    private List<String> columns;
    private List<List<String>> rows;
    private int rowCount;
    private String generatedSql;
    private String interpretationSummary;
    private long executionTimeMs;
    private String message;
    private String error;
}