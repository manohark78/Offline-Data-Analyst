// service/registry/TableMetadataRegistry.java
package com.enterprise.dataanalyst.service.registry;

import com.enterprise.dataanalyst.model.ColumnMetadata;
import com.enterprise.dataanalyst.model.TableMetadata;
import com.enterprise.dataanalyst.service.storage.DuckDBStorageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * In-memory index of all uploaded tables and their schemas.
 *
 * WHY A SEPARATE IN-MEMORY REGISTRY:
 * DuckDB has information_schema.columns — we could query it every time.
 * But for "which files contain column X", we'd need to join across all tables.
 * Having this in memory as a Map makes that operation O(n) without hitting the DB.
 *
 * The registry is populated at startup from DuckDB (persistent state)
 * and updated on each file upload.
 *
 * THREAD SAFETY: ConcurrentHashMap for concurrent reads during query processing.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class TableMetadataRegistry {

    private final DuckDBStorageService storageService;
    private final Map<String, TableMetadata> registry = new ConcurrentHashMap<>();

    /**
     * On application startup, reload all table metadata from DuckDB.
     * This ensures state survives application restarts.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void reloadFromPersistence() {
        try {
            List<TableMetadata> tables = storageService.loadAllTableMetadata();
            tables.forEach(t -> registry.put(t.getTableName(), t));
            log.info("TableMetadataRegistry loaded {} tables from persistent storage.", tables.size());
        } catch (SQLException e) {
            log.error("Failed to reload metadata from DuckDB. Starting with empty registry.", e);
        }
    }

    public void register(TableMetadata metadata) {
        registry.put(metadata.getTableName(), metadata);
        log.debug("Registered table '{}' with {} columns.", metadata.getTableName(),
                metadata.getColumns().size());
    }

    public Optional<TableMetadata> findByTableName(String tableName) {
        return Optional.ofNullable(registry.get(tableName));
    }

    /**
     * Find the best matching table by fuzzy name match.
     * "employees file" → matches table "employees"
     * "payroll sheet" → matches table "payroll"
     */
    public Optional<TableMetadata> findByFuzzyName(String nameHint) {
        if (nameHint == null || nameHint.isBlank()) return Optional.empty();
        String hint = nameHint.toLowerCase().trim();

        return registry.values().stream()
                .filter(t -> t.getTableName().contains(hint)
                        || t.getOriginalFileName().toLowerCase().contains(hint)
                        || hint.contains(t.getTableName()))
                .findFirst();
    }

    /**
     * Find all tables containing a column with the given name (partial match).
     * Used for: "which files have an email column"
     */
    public List<TableMetadata> findTablesWithColumn(String columnNameHint) {
        String hint = columnNameHint.toLowerCase().trim();
        return registry.values().stream()
                .filter(t -> t.hasColumn(hint))
                .collect(Collectors.toList());
    }

    /**
     * Find a table that contains ALL of the specified columns.
     * Used for intent enrichment when table is not explicitly named.
     * E.g., "average salary by department" → find table with both columns.
     */
    public Optional<TableMetadata> findTableWithColumns(List<String> columnHints) {
        return registry.values().stream()
                .filter(t -> columnHints.stream().allMatch(t::hasColumn))
                .findFirst();
    }

    public Collection<TableMetadata> getAllTables() {
        return Collections.unmodifiableCollection(registry.values());
    }

    public boolean isEmpty() {
        return registry.isEmpty();
    }

    public void remove(String tableName) {
        registry.remove(tableName);
        log.debug("Removed '{}' from registry.", tableName);
    }
}