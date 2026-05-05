package com.enterprise.dataanalyst.service.profiler;

import com.enterprise.dataanalyst.model.ColumnProfile;
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
 * In-memory index of all column profiles across all tables.
 *
 * WHY IN-MEMORY:
 * When the user asks "show females in Mumbai", we need to instantly find
 * which columns across ALL tables contain "Female" or "Mumbai" as values.
 * Querying DuckDB each time would be too slow. This cache makes it O(n)
 * where n = total columns across all tables.
 *
 * LOADED AT STARTUP from _sys_column_profiles table.
 * UPDATED on each new file upload by DataProfilerService.
 *
 * THREAD SAFETY: ConcurrentHashMap for concurrent reads during queries.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ColumnProfileRegistry {

    private final DuckDBStorageService storageService;

    // Key: "tableName.columnName" → ColumnProfile
    private final Map<String, ColumnProfile> profileMap = new ConcurrentHashMap<>();

    // Quick lookup: tableName → list of profiles for that table
    private final Map<String, List<ColumnProfile>> tableProfileMap = new ConcurrentHashMap<>();

    /**
     * Reload all profiles from DuckDB at startup.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void reloadFromPersistence() {
        try {
            List<ColumnProfile> profiles = storageService.loadAllColumnProfiles();
            profiles.forEach(p -> {
                String key = p.getTableName() + "." + p.getColumnName();
                profileMap.put(key, p);
            });

            // Build table→profiles index
            profiles.stream()
                    .collect(Collectors.groupingBy(ColumnProfile::getTableName))
                    .forEach(tableProfileMap::put);

            log.info("ColumnProfileRegistry loaded {} profiles across {} tables.",
                    profiles.size(), tableProfileMap.size());
        } catch (SQLException e) {
            log.error("Failed to load column profiles from DuckDB. " +
                      "Profiles will be built on next file upload.", e);
        }
    }

    /**
     * Register profiles for a table (called after profiling at upload time).
     */
    public void registerAll(String tableName, List<ColumnProfile> profiles) {
        // Remove old profiles for this table
        tableProfileMap.getOrDefault(tableName, List.of())
                .forEach(p -> profileMap.remove(tableName + "." + p.getColumnName()));

        // Add new profiles
        for (ColumnProfile p : profiles) {
            profileMap.put(tableName + "." + p.getColumnName(), p);
        }
        tableProfileMap.put(tableName, new ArrayList<>(profiles));

        log.debug("Registered {} profiles for table '{}'.", profiles.size(), tableName);
    }

    /**
     * Get all profiles for a specific table.
     */
    public List<ColumnProfile> getTableProfiles(String tableName) {
        return tableProfileMap.getOrDefault(tableName, List.of());
    }

    /**
     * Find columns across ALL tables that contain a specific value.
     * Case-insensitive matching.
     *
     * Example: findColumnsContainingValue("Female")
     * → returns profiles for columns that have "Female" in their distinct values.
     *
     * This is the key method used by SmartTableResolver and
     * DataAwarePromptBuilder for data-level resolution.
     */
    public List<ColumnProfile> findColumnsContainingValue(String value) {
        if (value == null || value.isBlank()) return List.of();
        return profileMap.values().stream()
                .filter(p -> p.containsValue(value))
                .collect(Collectors.toList());
    }

    /**
     * Get all categorical columns across all tables.
     * Used by DataAwarePromptBuilder to decide which columns
     * to annotate with distinct values in the LLM prompt.
     */
    public List<ColumnProfile> getAllCategoricalColumns() {
        return profileMap.values().stream()
                .filter(ColumnProfile::isCategorical)
                .collect(Collectors.toList());
    }

    /**
     * Get profile for a specific column in a specific table.
     */
    public Optional<ColumnProfile> getProfile(String tableName, String columnName) {
        return Optional.ofNullable(profileMap.get(tableName + "." + columnName));
    }

    /**
     * Check if any profiles exist (i.e., at least one table has been profiled).
     */
    public boolean hasProfiles() {
        return !profileMap.isEmpty();
    }

    /**
     * Check if a specific table has been profiled.
     */
    public boolean isProfiled(String tableName) {
        return tableProfileMap.containsKey(tableName);
    }

    /**
     * Remove profiles for a table (when table is dropped).
     */
    public void remove(String tableName) {
        tableProfileMap.getOrDefault(tableName, List.of())
                .forEach(p -> profileMap.remove(tableName + "." + p.getColumnName()));
        tableProfileMap.remove(tableName);
    }
}
