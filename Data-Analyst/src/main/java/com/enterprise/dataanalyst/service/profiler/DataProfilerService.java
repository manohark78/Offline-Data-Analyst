package com.enterprise.dataanalyst.service.profiler;

import com.enterprise.dataanalyst.model.ColumnMetadata;
import com.enterprise.dataanalyst.model.ColumnProfile;
import com.enterprise.dataanalyst.model.TableMetadata;
import com.enterprise.dataanalyst.service.registry.TableMetadataRegistry;
import com.enterprise.dataanalyst.service.storage.DuckDBStorageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.sql.SQLException;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Scans actual data values at upload time and builds a semantic profile
 * for every column in the table.
 *
 * WHY THIS EXISTS:
 * Schema tells us column "customer_segment" is VARCHAR.
 * Profiling tells us it contains {"Male", "Female"}.
 * Now the LLM can resolve "show females" → WHERE customer_segment = 'Female'.
 *
 * HOW IT WORKS:
 * 1. Reads data in chunks (configurable, default 200 rows)
 * 2. For each column, collects distinct values, counts, min/max
 * 3. Classifies each column's pattern (CATEGORICAL, NUMERIC_RANGE, etc.)
 * 4. Persists profiles to DuckDB system table _sys_column_profiles
 * 5. Registers profiles in ColumnProfileRegistry for fast in-memory access
 *
 * EARLY STOP OPTIMIZATION:
 * If all categorical columns have stabilized (no new distinct values
 * in the last chunk), scanning stops early. This means a table with
 * 100K rows but only 5 distinct cities won't scan all 100K rows.
 *
 * DATA STAYS LOCAL: All profiling happens within DuckDB on the local machine.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class DataProfilerService {

    private final DuckDBStorageService storageService;
    private final TableMetadataRegistry tableRegistry;
    private final ColumnProfileRegistry profileRegistry;

    @Value("${app.profiler.chunk-size:200}")
    private int chunkSize;

    @Value("${app.profiler.max-distinct-values:50}")
    private int maxDistinctValues;

    @Value("${app.profiler.enabled:true}")
    private boolean profilingEnabled;

    /**
     * Profile all columns in a table by scanning data in chunks.
     * Called after file upload + DuckDB load.
     */
    public void profileTable(String tableName) {
        if (!profilingEnabled) {
            log.info("Profiling disabled. Skipping table '{}'.", tableName);
            return;
        }

        log.info("Starting data profiling for table '{}'...", tableName);
        long startMs = System.currentTimeMillis();

        try {
            // Get table metadata for column info
            Optional<TableMetadata> tableMeta = tableRegistry.findByTableName(tableName);
            if (tableMeta.isEmpty()) {
                log.warn("Table '{}' not found in registry. Skipping profiling.", tableName);
                return;
            }

            List<ColumnMetadata> columns = tableMeta.get().getColumns();
            long totalRows = storageService.getRowCount(tableName);

            // Initialize accumulators for each column
            Map<String, ColumnAccumulator> accumulators = new LinkedHashMap<>();
            for (ColumnMetadata col : columns) {
                accumulators.put(col.getColumnName(),
                        new ColumnAccumulator(col.getColumnName(), col.getDataType()));
            }

            // Scan in chunks
            int offset = 0;
            int chunksScanned = 0;
            boolean allStabilized = false;

            while (offset < totalRows && !allStabilized) {
                List<Map<String, Object>> chunk =
                        storageService.scanChunk(tableName, chunkSize, offset);

                if (chunk.isEmpty()) break;

                chunksScanned++;
                log.debug("Scanning chunk {} (rows {}-{}) of table '{}'",
                        chunksScanned, offset + 1,
                        offset + chunk.size(), tableName);

                // Process each row in the chunk
                for (Map<String, Object> row : chunk) {
                    for (var entry : row.entrySet()) {
                        ColumnAccumulator acc = accumulators.get(entry.getKey());
                        if (acc != null) {
                            acc.addValue(entry.getValue());
                        }
                    }
                }

                offset += chunk.size();

                // Check early stop: all categorical columns stabilized?
                // (no new distinct values found in this chunk)
                if (chunksScanned >= 2) {
                    allStabilized = accumulators.values().stream()
                            .allMatch(ColumnAccumulator::isStabilized);
                    if (allStabilized) {
                        log.info("All columns stabilized after {} chunks ({} rows). " +
                                 "Stopping early.", chunksScanned, offset);
                    }
                }
            }

            // Build ColumnProfile objects from accumulators
            List<ColumnProfile> profiles = accumulators.values().stream()
                    .map(acc -> acc.buildProfile(tableName, totalRows, maxDistinctValues))
                    .collect(Collectors.toList());

            // Persist to DuckDB
            storageService.persistColumnProfiles(tableName, profiles);

            // Register in memory
            profileRegistry.registerAll(tableName, profiles);

            long elapsed = System.currentTimeMillis() - startMs;
            log.info("Profiling complete for '{}': {} columns, {} chunks scanned, {}ms",
                    tableName, profiles.size(), chunksScanned, elapsed);

        } catch (SQLException e) {
            log.error("Profiling failed for table '{}': {}", tableName, e.getMessage(), e);
            // Profiling failure should NOT block the upload — it's an enhancement
        }
    }

    // ─────────────────────────────────────────────────────────────
    // INNER CLASS: Column accumulator for incremental profiling
    // ─────────────────────────────────────────────────────────────

    /**
     * Accumulates statistics about a single column across multiple chunks.
     * Tracks distinct values, nulls, min/max, and detects stabilization.
     */
    private static class ColumnAccumulator {
        private final String columnName;
        private final String dataType;
        private final Set<String> distinctValues = new LinkedHashSet<>();
        private final List<String> sampleValues = new ArrayList<>();
        private long nullCount = 0;
        private long rowCount = 0;
        private String minValue = null;
        private String maxValue = null;

        // For stabilization detection
        private int previousDistinctCount = 0;
        private boolean stabilized = false;
        private boolean distinctCapped = false;

        ColumnAccumulator(String columnName, String dataType) {
            this.columnName = columnName;
            this.dataType = dataType;
        }

        void addValue(Object value) {
            rowCount++;

            if (value == null) {
                nullCount++;
                return;
            }

            String strValue = String.valueOf(value).trim();
            if (strValue.isEmpty() || strValue.equalsIgnoreCase("null")) {
                nullCount++;
                return;
            }

            // Collect sample values (first 5 unique)
            if (sampleValues.size() < 5 && !sampleValues.contains(strValue)) {
                sampleValues.add(strValue);
            }

            // Track distinct values (cap at a reasonable limit to avoid OOM)
            if (!distinctCapped) {
                distinctValues.add(strValue);
                if (distinctValues.size() > 500) {
                    // Too many distinct values — this is likely free text
                    distinctCapped = true;
                }
            }

            // Track min/max for numeric and date types
            updateMinMax(strValue);
        }

        private void updateMinMax(String strValue) {
            if (isNumericType()) {
                try {
                    double val = Double.parseDouble(strValue.replace(",", ""));
                    if (minValue == null || val < Double.parseDouble(minValue.replace(",", ""))) {
                        minValue = strValue;
                    }
                    if (maxValue == null || val > Double.parseDouble(maxValue.replace(",", ""))) {
                        maxValue = strValue;
                    }
                } catch (NumberFormatException ignored) {
                    // Skip non-numeric values in numeric columns
                }
            } else if ("DATE".equals(dataType)) {
                if (minValue == null || strValue.compareTo(minValue) < 0) {
                    minValue = strValue;
                }
                if (maxValue == null || strValue.compareTo(maxValue) > 0) {
                    maxValue = strValue;
                }
            }
        }

        boolean isStabilized() {
            if (stabilized) return true;

            int currentDistinct = distinctValues.size();
            if (currentDistinct == previousDistinctCount) {
                stabilized = true; // No new values in this chunk
            }
            previousDistinctCount = currentDistinct;
            return stabilized;
        }

        ColumnProfile buildProfile(String tableName, long totalRows,
                                    int maxDistinctForCategorical) {
            int actualDistinct = distinctValues.size();
            String pattern;

            if ("BOOLEAN".equals(dataType)) {
                pattern = "BOOLEAN_FLAG";
            } else if (isNumericType()) {
                pattern = "NUMERIC_RANGE";
            } else if ("DATE".equals(dataType)) {
                pattern = "DATE_RANGE";
            } else if (actualDistinct <= maxDistinctForCategorical && !distinctCapped) {
                pattern = "CATEGORICAL";
            } else {
                pattern = "FREE_TEXT";
            }

            // For categorical: keep all distinct values (capped)
            // For others: clear distinct values to save memory
            Set<String> finalDistinct;
            if ("CATEGORICAL".equals(pattern)) {
                finalDistinct = new LinkedHashSet<>(distinctValues);
            } else {
                finalDistinct = Set.of();
            }

            return ColumnProfile.builder()
                    .tableName(tableName)
                    .columnName(columnName)
                    .dataType(dataType)
                    .distinctValues(finalDistinct)
                    .distinctCount(actualDistinct)
                    .nullCount(nullCount)
                    .totalCount(totalRows)
                    .minValue(minValue)
                    .maxValue(maxValue)
                    .dominantPattern(pattern)
                    .sampleValues(new ArrayList<>(sampleValues))
                    .build();
        }

        private boolean isNumericType() {
            return "BIGINT".equals(dataType) || "DOUBLE".equals(dataType);
        }
    }
}
