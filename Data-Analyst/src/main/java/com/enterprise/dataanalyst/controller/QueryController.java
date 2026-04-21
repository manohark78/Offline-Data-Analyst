package com.enterprise.dataanalyst.controller;

import com.enterprise.dataanalyst.dto.QueryRequest;
import com.enterprise.dataanalyst.dto.QueryResponse;
import com.enterprise.dataanalyst.exception.QueryProcessingException;
import com.enterprise.dataanalyst.model.TableMetadata;
import com.enterprise.dataanalyst.nlp.IntentAction;
import com.enterprise.dataanalyst.nlp.QueryIntent;
import com.enterprise.dataanalyst.service.llm.IntentBasedSQLGenerator;
import com.enterprise.dataanalyst.service.llm.SemanticIntentClassifier;
import com.enterprise.dataanalyst.service.query.QueryExecutorService;
import com.enterprise.dataanalyst.service.registry.TableMetadataRegistry;
import com.enterprise.dataanalyst.service.result.ResultFormatterService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Collection;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/query")
@RequiredArgsConstructor
@Slf4j
public class QueryController {

    private final SemanticIntentClassifier intentClassifier;
    private final IntentBasedSQLGenerator sqlGenerator;
    private final QueryExecutorService queryExecutorService;
    private final ResultFormatterService resultFormatterService;
    private final TableMetadataRegistry registry;

    @PostMapping
    public ResponseEntity<QueryResponse> query(@Valid @RequestBody QueryRequest request) {
        log.info("Received query: '{}'", request.getQuery());

        if (registry.isEmpty()) {
            return ResponseEntity.ok(QueryResponse.builder()
                    .message("No files uploaded yet. Please upload a file first.")
                    .build());
        }

        long startTime = System.currentTimeMillis();

        try {
            Collection<TableMetadata> allTables = registry.getAllTables();

            // Step 1: AI classifies intent semantically
            QueryIntent intent = intentClassifier.classify(request.getQuery(), allTables);

            // Low confidence — ask for clarification
            if (intent.getAction() == IntentAction.UNKNOWN) {
                return ResponseEntity.ok(QueryResponse.builder()
                        .interpretationSummary(intent.getInterpretationSummary())
                        .message(intent.getInterpretationSummary())
                        .build());
            }

            // Step 2: Handle FIND_FILES_WITH_COLUMN via registry
            if (intent.getAction() == IntentAction.FIND_FILES_WITH_COLUMN) {
                List<Map<String, Object>> results =
                        queryExecutorService.execute(intent, null);
                long elapsed = System.currentTimeMillis() - startTime;
                return ResponseEntity.ok(
                        resultFormatterService.format(intent, null, results, elapsed));
            }

            // Step 3: Generate deterministic SQL from intent
            String sql = sqlGenerator.generate(intent);

            // Step 4: Execute against DuckDB
            List<Map<String, Object>> rawResults =
                    queryExecutorService.execute(intent, sql);

            long elapsed = System.currentTimeMillis() - startTime;

            // Step 5: Format and return
            return ResponseEntity.ok(
                    resultFormatterService.format(intent, sql, rawResults, elapsed));

        } catch (QueryProcessingException e) {
            log.warn("Query error: {}", e.getMessage());
            return ResponseEntity.badRequest()
                    .body(QueryResponse.builder().error(e.getMessage()).build());
        } catch (Exception e) {
            log.error("Unexpected error: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError()
                    .body(QueryResponse.builder()
                            .error("Unexpected error. Check server logs.").build());
        }
    }
}