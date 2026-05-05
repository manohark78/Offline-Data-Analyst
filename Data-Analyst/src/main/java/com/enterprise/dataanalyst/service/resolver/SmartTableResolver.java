package com.enterprise.dataanalyst.service.resolver;

import com.enterprise.dataanalyst.model.ColumnProfile;
import com.enterprise.dataanalyst.model.TableMetadata;
import com.enterprise.dataanalyst.service.profiler.ColumnProfileRegistry;
import com.enterprise.dataanalyst.service.registry.TableMetadataRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * Data-aware table disambiguation.
 *
 * WHY THIS REPLACES SIMPLE NAME MATCHING:
 * When "category" exists in both "orders" and "sheets" tables,
 * name matching alone can't decide. But if the user says "UPI purchases"
 * and only the "orders" table has a column containing "UPI" as a value,
 * that's a definitive signal.
 *
 * SCORING SYSTEM:
 * Each table gets scored based on how well it matches the query:
 *   +10  exact column name match
 *   +15  query value found in column's profiled data (strongest signal)
 *   +5   partial column name match
 *   +3   each additional column match
 *   +2   single table bonus (only one table loaded)
 *
 * EXAMPLE:
 * Query: "Display product_category purchases done using UPI"
 * Table "orders":  product_category match (+10) + "UPI" in payment_mode (+15) = 25 ✅
 * Table "sheets":  category partial match (+5) + no "UPI" anywhere (+0) = 5  ❌
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SmartTableResolver {

    private final TableMetadataRegistry tableRegistry;
    private final ColumnProfileRegistry profileRegistry;

    // Scoring weights
    private static final int EXACT_COLUMN_MATCH = 10;
    private static final int VALUE_FOUND_IN_DATA = 15;
    private static final int PARTIAL_COLUMN_MATCH = 5;
    private static final int ADDITIONAL_COLUMN_MATCH = 3;
    private static final int SINGLE_TABLE_BONUS = 2;

    /**
     * Resolve the best matching table for a user query.
     * Uses both schema-level and data-level signals.
     *
     * @param userQuery The raw natural language query
     * @return Best matching table name, or null if no tables loaded
     */
    public String resolve(String userQuery) {
        Collection<TableMetadata> allTables = tableRegistry.getAllTables();
        if (allTables.isEmpty()) return null;
        if (allTables.size() == 1) {
            return allTables.iterator().next().getTableName();
        }

        String lower = userQuery.toLowerCase().trim();
        String[] queryWords = lower.split("\\s+");

        // Score each table
        Map<String, Integer> scores = new LinkedHashMap<>();
        Map<String, String> reasons = new LinkedHashMap<>();

        for (TableMetadata table : allTables) {
            int score = 0;
            StringBuilder reason = new StringBuilder();

            // ── 1. Schema-level scoring ─────────────────────────

            // Exact table name match in query
            if (lower.contains(table.getTableName().toLowerCase())) {
                score += EXACT_COLUMN_MATCH;
                reason.append("table name match; ");
            }

            // File name match
            String baseName = table.getOriginalFileName()
                    .replaceAll("\\.[^.]+$", "").toLowerCase();
            if (lower.contains(baseName)) {
                score += EXACT_COLUMN_MATCH;
                reason.append("file name match; ");
            }

            // Column name matches
            int colMatches = 0;
            for (var col : table.getColumns()) {
                String colLower = col.getColumnName().toLowerCase();
                String origLower = col.getOriginalName().toLowerCase();

                // Exact column match
                if (lower.contains(colLower) || lower.contains(origLower)) {
                    score += (colMatches == 0) ? EXACT_COLUMN_MATCH : ADDITIONAL_COLUMN_MATCH;
                    colMatches++;
                    reason.append("column '").append(col.getColumnName()).append("' match; ");
                } else {
                    // Partial match — check each query word
                    for (String word : queryWords) {
                        if (word.length() > 3 &&
                                (colLower.contains(word) || word.contains(colLower))) {
                            score += PARTIAL_COLUMN_MATCH;
                            reason.append("partial '").append(word)
                                  .append("'≈'").append(col.getColumnName()).append("'; ");
                            break;
                        }
                    }
                }
            }

            // ── 2. Data-level scoring (the key differentiator) ──

            if (profileRegistry.isProfiled(table.getTableName())) {
                List<ColumnProfile> profiles =
                        profileRegistry.getTableProfiles(table.getTableName());

                for (String word : queryWords) {
                    if (word.length() <= 2) continue; // Skip "in", "of", etc.

                    for (ColumnProfile profile : profiles) {
                        if (profile.containsValue(word)) {
                            score += VALUE_FOUND_IN_DATA;
                            reason.append("value '").append(word)
                                  .append("' found in column '")
                                  .append(profile.getColumnName()).append("'; ");
                            break; // One match per word per table
                        }
                    }
                }
            }

            scores.put(table.getTableName(), score);
            reasons.put(table.getTableName(), reason.toString());
        }

        // Find the winner
        String bestTable = scores.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey)
                .orElse(null);

        int bestScore = scores.getOrDefault(bestTable, 0);

        if (bestScore > 0) {
            log.info("Table resolution: '{}' (score={}) — {}",
                    bestTable, bestScore, reasons.get(bestTable));

            // Log runner-up for debugging
            scores.entrySet().stream()
                    .filter(e -> !e.getKey().equals(bestTable) && e.getValue() > 0)
                    .forEach(e -> log.debug("  Runner-up: '{}' (score={}) — {}",
                            e.getKey(), e.getValue(), reasons.get(e.getKey())));

            return bestTable;
        }

        // Fallback: no scoring signals — return first table or null
        log.warn("No scoring signals for query '{}'. Tables: {}",
                userQuery, scores.keySet());
        return null;
    }
}
