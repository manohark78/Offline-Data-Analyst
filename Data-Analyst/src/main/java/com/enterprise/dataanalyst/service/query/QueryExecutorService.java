// service/query/QueryExecutorService.java
package com.enterprise.dataanalyst.service.query;

import com.enterprise.dataanalyst.exception.QueryProcessingException;
import com.enterprise.dataanalyst.model.TableMetadata;
import com.enterprise.dataanalyst.nlp.IntentAction;
import com.enterprise.dataanalyst.nlp.QueryIntent;
import com.enterprise.dataanalyst.service.registry.TableMetadataRegistry;
import com.enterprise.dataanalyst.service.storage.DuckDBStorageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Executes the generated SQL against DuckDB and handles special non-SQL queries.
 *
 * TWO EXECUTION PATHS:
 *
 * 1. STANDARD PATH (most queries):
 *    SQL string → DuckDB → List<Map<String, Object>>
 *
 * 2. REGISTRY PATH (FIND_FILES_WITH_COLUMN):
 *    This action doesn't query DuckDB. It searches the in-memory metadata registry.
 *    No SQL needed. We format the result to look the same as a DuckDB result.
 *
 * WHY SEPARATE THIS FROM SQLGeneratorService:
 * SQLGeneratorService knows how to write SQL.
 * QueryExecutorService knows where to run it.
 * Separation of concerns — if we ever add a second data store, we add a routing
 * strategy here without touching SQL generation.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class QueryExecutorService {

    private final DuckDBStorageService storageService;
    private final TableMetadataRegistry registry;

    public List<Map<String, Object>> execute(QueryIntent intent, String sql) {
        // Special case: registry query, no SQL needed
        if (intent.getAction() == IntentAction.FIND_FILES_WITH_COLUMN) {
            return executeRegistryQuery(intent);
        }

        // Standard DuckDB execution
        if (sql == null) {
            throw new QueryProcessingException("SQL generation failed for intent: " + intent.getAction());
        }

        try {
            long startMs = System.currentTimeMillis();
            List<Map<String, Object>> results = storageService.executeQuery(sql);
            long elapsed = System.currentTimeMillis() - startMs;
            log.info("Query executed in {}ms, returned {} rows.", elapsed, results.size());
            return results;
        } catch (SQLException e) {
            log.error("DuckDB execution error for SQL [{}]: {}", sql, e.getMessage());
            throw new QueryProcessingException(
                    "Query execution failed: " + e.getMessage() +
                    ". The generated SQL was: " + sql, e);
        }
    }

    /**
     * Search the metadata registry for tables containing the specified column.
     * Returns results in the same Map format as DuckDB results for consistency.
     */
    private List<Map<String, Object>> executeRegistryQuery(QueryIntent intent) {
        String columnHint = intent.getTargetColumn();
        if (columnHint == null) {
            throw new QueryProcessingException(
                    "Please specify which column to search for. " +
                    "Example: 'which files contain email column'");
        }

        List<TableMetadata> matchingTables = registry.findTablesWithColumn(columnHint);

        if (matchingTables.isEmpty()) {
            // Return an informative empty result
            return List.of(Map.of("message",
                    "No uploaded files contain a column matching '" + columnHint + "'"));
        }

        return matchingTables.stream()
                .map(table -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("file_name", table.getOriginalFileName());
                    row.put("table_name", table.getTableName());
                    row.put("matching_column", table.resolveColumn(columnHint));
                    row.put("total_columns", table.getColumns().size());
                    row.put("row_count", table.getRowCount());
                    return row;
                })
                .collect(Collectors.toList());
    }

    public List<Map<String, Object>> executeRaw(String sql) {
        log.debug("Executing: {}", sql);

        // Remove trailing semicolon — some DuckDB versions
        // handle it differently via JDBC
        String cleanSql = sql.trim();
        if (cleanSql.endsWith(";")) {
            cleanSql = cleanSql.substring(0, cleanSql.length() - 1);
        }

        try {
            return storageService.executeQuery(cleanSql);
        } catch (SQLException e) {
            log.error("DuckDB error for SQL [{}]: {}", cleanSql, e.getMessage());
            throw new QueryProcessingException(
                    "Database execution failed: " + e.getMessage(), e);
        }
    }
}