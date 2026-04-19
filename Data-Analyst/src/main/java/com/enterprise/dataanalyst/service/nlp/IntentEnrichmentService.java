// service/nlp/IntentEnrichmentService.java
package com.enterprise.dataanalyst.service.nlp;

import com.enterprise.dataanalyst.model.TableMetadata;
import com.enterprise.dataanalyst.nlp.IntentAction;
import com.enterprise.dataanalyst.nlp.QueryIntent;
import com.enterprise.dataanalyst.service.registry.TableMetadataRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Resolves loose entity hints from QueryIntent against actual table metadata.
 *
 * WHY A SEPARATE ENRICHMENT STEP:
 * The intent parser works on text alone. It extracts hints like "salary" or "employees".
 * Enrichment validates and resolves these hints against what's actually in DuckDB.
 *
 * EXAMPLE:
 *   Intent parser outputs: targetTable="employ" (partial), targetColumn="sal"
 *   Enrichment resolves:   targetTable="employees", targetColumn="salary"
 *
 * If a table has only one table loaded and no table was specified, enrichment
 * automatically assigns that single table — no user friction.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class IntentEnrichmentService {

    private final TableMetadataRegistry registry;

    public QueryIntent enrich(QueryIntent intent) {
        if (intent.getAction() == IntentAction.UNKNOWN) {
            return intent; // nothing to enrich
        }

        // FIND_FILES_WITH_COLUMN doesn't need a specific table
        if (intent.getAction() == IntentAction.FIND_FILES_WITH_COLUMN) {
            return intent;
        }

        // Resolve table
        String resolvedTable = resolveTable(intent);
        if (resolvedTable == null && registry.getAllTables().size() == 1) {
            // Auto-select the only available table
            resolvedTable = registry.getAllTables().iterator().next().getTableName();
            log.debug("Auto-selected single available table: '{}'", resolvedTable);
        }

        if (resolvedTable == null) {
            // Try to find by column hints
            List<String> hints = new ArrayList<>();
            if (intent.getTargetColumn() != null) hints.add(intent.getTargetColumn());
            if (intent.getGroupByColumn() != null) hints.add(intent.getGroupByColumn());
            if (!hints.isEmpty()) {
                Optional<TableMetadata> byColumns = registry.findTableWithColumns(hints);
                resolvedTable = byColumns.map(TableMetadata::getTableName).orElse(null);
                if (resolvedTable != null) {
                    log.debug("Resolved table '{}' by column hints {}", resolvedTable, hints);
                }
            }
        }

        // Resolve column names against actual table schema
        String resolvedColumn = null;
        String resolvedGroupBy = null;

        if (resolvedTable != null) {
            Optional<TableMetadata> tableMeta = registry.findByTableName(resolvedTable);
            if (tableMeta.isPresent()) {
                TableMetadata meta = tableMeta.get();

                if (intent.getTargetColumn() != null) {
                    resolvedColumn = meta.resolveColumn(intent.getTargetColumn());
                    if (resolvedColumn == null) {
                        log.warn("Column hint '{}' not found in table '{}'",
                                intent.getTargetColumn(), resolvedTable);
                    }
                }

                if (intent.getGroupByColumn() != null) {
                    resolvedGroupBy = meta.resolveColumn(intent.getGroupByColumn());
                }
            }
        }

        return QueryIntent.builder()
                .action(intent.getAction())
                .targetTable(resolvedTable)
                .targetColumn(resolvedColumn != null ? resolvedColumn : intent.getTargetColumn())
                .groupByColumn(resolvedGroupBy != null ? resolvedGroupBy : intent.getGroupByColumn())
                .aggregateFunction(intent.getAggregateFunction())
                .limit(intent.getLimit())
                .rawQuery(intent.getRawQuery())
                .confidence(intent.getConfidence())
                .interpretationSummary(intent.getInterpretationSummary())
                .build();
    }

    private String resolveTable(QueryIntent intent) {
        if (intent.getTargetTable() == null) return null;

        // Exact match
        Optional<TableMetadata> exact = registry.findByTableName(intent.getTargetTable());
        if (exact.isPresent()) return exact.get().getTableName();

        // Fuzzy match
        Optional<TableMetadata> fuzzy = registry.findByFuzzyName(intent.getTargetTable());
        return fuzzy.map(TableMetadata::getTableName).orElse(null);
    }
}