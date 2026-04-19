// service/result/ResultFormatterService.java
package com.enterprise.dataanalyst.service.result;

import com.enterprise.dataanalyst.dto.QueryResponse;
import com.enterprise.dataanalyst.nlp.QueryIntent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Formats raw DuckDB results into a structured QueryResponse DTO.
 *
 * WHY THIS EXISTS:
 * DuckDB returns List<Map<String, Object>> with mixed types (Long, Double, String, Date).
 * The REST layer needs consistent JSON. We convert everything to displayable strings
 * while preserving column order.
 */
@Service
@Slf4j
public class ResultFormatterService {

    public QueryResponse format(QueryIntent intent, String generatedSql,
                                 List<Map<String, Object>> rawResults,
                                 long executionTimeMs) {

        if (rawResults == null || rawResults.isEmpty()) {
            return QueryResponse.builder()
                    .columns(List.of())
                    .rows(List.of())
                    .rowCount(0)
                    .generatedSql(generatedSql)
                    .interpretationSummary(intent.getInterpretationSummary())
                    .executionTimeMs(executionTimeMs)
                    .message("Query returned no results.")
                    .build();
        }

        // Column names from the first row (LinkedHashMap preserves order)
        List<String> columns = new ArrayList<>(rawResults.get(0).keySet());

        // Convert each row's values to strings
        List<List<String>> rows = new ArrayList<>();
        for (Map<String, Object> rawRow : rawResults) {
            List<String> row = new ArrayList<>();
            for (String col : columns) {
                Object value = rawRow.get(col);
                row.add(formatValue(value));
            }
            rows.add(row);
        }

        return QueryResponse.builder()
                .columns(columns)
                .rows(rows)
                .rowCount(rows.size())
                .generatedSql(generatedSql)
                .interpretationSummary(intent.getInterpretationSummary())
                .executionTimeMs(executionTimeMs)
                .build();
    }

    private String formatValue(Object value) {
        if (value == null) return "NULL";
        // Numbers: avoid scientific notation for large integers
        if (value instanceof Double) {
            double d = (Double) value;
            if (d == Math.floor(d) && !Double.isInfinite(d)) {
                return String.valueOf((long) d);
            }
        }
        return String.valueOf(value);
    }
}