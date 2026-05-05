// dto/QueryRequest.java
package com.enterprise.dataanalyst.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;


@Data
public class QueryRequest {
    @NotBlank(message = "Query cannot be empty")
    @Size(max = 500, message = "Query too long")
    private String query;
    private String sessionId;
}