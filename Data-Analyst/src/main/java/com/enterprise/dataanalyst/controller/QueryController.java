package com.enterprise.dataanalyst.controller;

import com.enterprise.dataanalyst.dto.QueryRequest;
import com.enterprise.dataanalyst.dto.QueryResponse;
import com.enterprise.dataanalyst.exception.QueryProcessingException;
import com.enterprise.dataanalyst.service.history.QueryHistoryService;
import com.enterprise.dataanalyst.service.llm.SQLCoderService;
import com.enterprise.dataanalyst.service.query.QueryExecutorService;
import com.enterprise.dataanalyst.service.registry.TableMetadataRegistry;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/query")
@RequiredArgsConstructor
@Slf4j
public class QueryController {

    private final SQLCoderService sqlCoderService;
    private final QueryExecutorService queryExecutorService;
    private final QueryHistoryService historyService;
    private final TableMetadataRegistry registry;

    @PostMapping
    public ResponseEntity<QueryResponse> query(
            @Valid @RequestBody QueryRequest request) {

        log.info("Query received: '{}'", request.getQuery());
        long start = System.currentTimeMillis();

        if (registry.isEmpty()) {
            return ResponseEntity.ok(QueryResponse.builder()
                    .message("No files uploaded yet. Please upload a file first.")
                    .build());
        }

        String generatedSql = null;

        try {
            // Step 1: Generate SQL
            generatedSql = sqlCoderService.generateSQL(request.getQuery());
            log.info("SQL to execute: {}", generatedSql);

            // Step 2: ALWAYS execute — never return SQL only
            List<Map<String, Object>> rawResults =
                    queryExecutorService.executeRaw(generatedSql);

            long elapsed = System.currentTimeMillis() - start;

            // Step 3: Build columns and rows
            List<String> columns = rawResults.isEmpty()
                    ? List.of()
                    : new ArrayList<>(rawResults.get(0).keySet());

            List<List<String>> rows = new ArrayList<>();
            for (Map<String, Object> row : rawResults) {
                List<String> rowData = new ArrayList<>();
                for (Object val : row.values()) {
                    rowData.add(val == null ? "NULL" : String.valueOf(val));
                }
                rows.add(rowData);
            }

            // Step 4: Save to history
            historyService.save(
                request.getQuery(), generatedSql,
                "SUCCESS", rawResults.size(), elapsed);

            return ResponseEntity.ok(QueryResponse.builder()
                    .columns(columns)
                    .rows(rows)
                    .rowCount(rawResults.size())
                    .generatedSql(generatedSql)
                    .interpretationSummary(buildSummary(
                        request.getQuery(), rawResults.size(), elapsed))
                    .executionTimeMs(elapsed)
                    .build());

        } catch (QueryProcessingException e) {
            long elapsed = System.currentTimeMillis() - start;
            historyService.save(
                request.getQuery(), generatedSql, "ERROR", 0, elapsed);

            log.warn("Query failed: {}", e.getMessage());
            return ResponseEntity.badRequest()
                    .body(QueryResponse.builder()
                            .error(e.getMessage())
                            .generatedSql(generatedSql)
                            .build());

        } catch (Exception e) {
            long elapsed = System.currentTimeMillis() - start;
            historyService.save(
                request.getQuery(), generatedSql, "ERROR", 0, elapsed);

            log.error("Unexpected error: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError()
                    .body(QueryResponse.builder()
                            .error("Unexpected error: " + e.getMessage())
                            .build());
        }
    }

    /**
     * Get query history — persists across restarts.
     */
    @GetMapping("/history")
    public ResponseEntity<List<Map<String, Object>>> getHistory(
            @RequestParam(defaultValue = "50") int limit) {
        return ResponseEntity.ok(historyService.getRecent(limit));
    }

    /**
     * Search history by keyword.
     */
    @GetMapping("/history/search")
    public ResponseEntity<List<Map<String, Object>>> searchHistory(
            @RequestParam String q) {
        return ResponseEntity.ok(historyService.search(q));
    }

    /**
     * Clear history.
     */
    @DeleteMapping("/history")
    public ResponseEntity<Map<String, String>> clearHistory() {
        historyService.clear();
        return ResponseEntity.ok(Map.of("message", "History cleared."));
    }

    private String buildSummary(String query, int rowCount, long ms) {
        return "Query executed in " + ms + "ms — " +
               rowCount + " row(s) returned";
    }
}
