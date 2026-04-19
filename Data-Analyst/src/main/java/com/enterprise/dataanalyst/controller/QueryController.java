// controller/QueryController.java
package com.enterprise.dataanalyst.controller;

import com.enterprise.dataanalyst.dto.QueryRequest;
import com.enterprise.dataanalyst.dto.QueryResponse;
import com.enterprise.dataanalyst.nlp.QueryIntent;
import com.enterprise.dataanalyst.service.nlp.IntentEnrichmentService;
import com.enterprise.dataanalyst.service.nlp.IntentParserService;
import com.enterprise.dataanalyst.service.query.QueryExecutorService;
import com.enterprise.dataanalyst.service.query.SQLGeneratorService;
import com.enterprise.dataanalyst.service.registry.TableMetadataRegistry;
import com.enterprise.dataanalyst.service.result.ResultFormatterService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/query")
@RequiredArgsConstructor
@Slf4j
public class QueryController {

    private final IntentParserService intentParser;
    private final IntentEnrichmentService enrichmentService;
    private final SQLGeneratorService sqlGenerator;
    private final QueryExecutorService queryExecutor;
    private final ResultFormatterService resultFormatter;
    private final TableMetadataRegistry registry;

    @Value("${app.nlp.confidence-threshold:0.6}")
    private double confidenceThreshold;

    @PostMapping
    public ResponseEntity<QueryResponse> query(@Valid @RequestBody QueryRequest request) {
        log.info("Received query: '{}'", request.getQuery());

        if (registry.isEmpty()) {
            return ResponseEntity.ok(QueryResponse.builder()
                    .message("No files uploaded yet. Please upload a CSV or Excel file first.")
                    .build());
        }

        long startTime = System.currentTimeMillis();

        // Step 1: Parse intent
        QueryIntent intent = intentParser.parse(request.getQuery(), registry.getAllTables());

        // Step 2: Check confidence threshold
        if (intent.getConfidence() < confidenceThreshold) {
            return ResponseEntity.ok(QueryResponse.builder()
                    .interpretationSummary(intent.getInterpretationSummary())
                    .message("Query not understood with sufficient confidence. " +
                            "Try: 'count rows in employees', 'average salary by department', " +
                            "'find duplicates in employee_id'")
                    .build());
        }

        // Step 3: Enrich intent (resolve table/column names)
        QueryIntent enrichedIntent = enrichmentService.enrich(intent);

        // Step 4: Generate SQL
        String sql = sqlGenerator.generate(enrichedIntent);

        // Step 5: Execute
        List<Map<String, Object>> rawResults = queryExecutor.execute(enrichedIntent, sql);

        long elapsed = System.currentTimeMillis() - startTime;

        // Step 6: Format and return
        QueryResponse response = resultFormatter.format(enrichedIntent, sql, rawResults, elapsed);
        return ResponseEntity.ok(response);
    }
}