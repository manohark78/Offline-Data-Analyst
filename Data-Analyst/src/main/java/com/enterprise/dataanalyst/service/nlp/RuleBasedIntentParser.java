// service/nlp/RuleBasedIntentParser.java
package com.enterprise.dataanalyst.service.nlp;

import com.enterprise.dataanalyst.model.TableMetadata;
import com.enterprise.dataanalyst.nlp.AggregateFunction;
import com.enterprise.dataanalyst.nlp.IntentAction;
import com.enterprise.dataanalyst.nlp.QueryIntent;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Rule-based intent parser using keyword matching and regex.
 *
 * ALGORITHM OVERVIEW:
 * 1. Normalize the query (lowercase, strip punctuation)
 * 2. Match against ordered pattern groups → derive IntentAction
 * 3. Extract entity hints: table name, column names, group-by column
 * 4. Calculate confidence score
 * 5. Build human-readable interpretation summary
 *
 * WHY ORDERED PATTERNS:
 * Some queries match multiple patterns. E.g., "count duplicate employee_id"
 * matches both COUNT and DUPLICATE patterns. We order patterns by specificity,
 * most specific first. FIND_DUPLICATES is checked before COUNT_ROWS.
 *
 * ENTITY EXTRACTION STRATEGY:
 * We don't try to do full NLP (POS tagging, NER). Instead we use simple
 * heuristics:
 * - Table hint: last word in "in X file/table/sheet" patterns
 * - Column hint: noun following the action keyword (e.g., "duplicate [COLUMN]")
 * - Group-by hint: noun following "by" or "per" (e.g., "by [COLUMN]")
 *
 * IntentEnrichmentService then resolves these loose hints against actual metadata.
 */
@Service
@Slf4j
public class RuleBasedIntentParser implements IntentParserService {

    // ─── ACTION DETECTION PATTERNS ───────────────────────────────

    private static final List<Pattern> FIND_DUPLICATES_PATTERNS = List.of(
            Pattern.compile("(?i)(find|show|detect|get|list).*?duplicate"),
            Pattern.compile("(?i)duplicate.*?(in|on|for)"),
            Pattern.compile("(?i)(repeated|duplicated).*?(values?|entries|rows?)")
    );

    private static final List<Pattern> SHOW_MISSING_PATTERNS = List.of(
            Pattern.compile("(?i)(missing|null|empty|blank|absent).*?(value|data|field|column|row)"),
            Pattern.compile("(?i)(show|find|count).*?(missing|null|empty|blank)"),
            Pattern.compile("(?i)where.*?is.*(null|empty|missing)")
    );

    private static final List<Pattern> COUNT_ROWS_PATTERNS = List.of(
            Pattern.compile("(?i)(count|how many|total number of|number of).*?(row|record|entry|entries|line)"),
            Pattern.compile("(?i)(row|record).*?(count|number|total)"),
            Pattern.compile("(?i)how many rows")
    );

    private static final List<Pattern> SHOW_COLUMNS_PATTERNS = List.of(
            Pattern.compile("(?i)(show|list|display|get|what|which).*?(column|field|header|schema|structure)"),
            Pattern.compile("(?i)(column|field|header).*?(name|list|available)"),
            Pattern.compile("(?i)describe.*?(table|file|sheet|data)")
    );

    private static final List<Pattern> SHOW_SAMPLE_PATTERNS = List.of(
            Pattern.compile("(?i)(show|display|preview|get).*?(data|row|sample|record)"),
            Pattern.compile("(?i)(first|top|last)\\s+\\d+.*?(row|record)"),
            Pattern.compile("(?i)(sample|preview|head|tail)")
    );

    private static final List<Pattern> SHOW_DISTINCT_PATTERNS = List.of(
            Pattern.compile("(?i)(distinct|unique|different).*?(value|option|entry)"),
            Pattern.compile("(?i)(what|list).*(distinct|unique)"),
            Pattern.compile("(?i)possible values")
    );

    private static final List<Pattern> FIND_FILES_PATTERNS = List.of(
            Pattern.compile("(?i)(which|what).*(file|table|sheet).*?(have|has|contain|with)"),
            Pattern.compile("(?i)(file|table|sheet).*(contain|have|with).*?(column|field)")
    );

    // ─── AGGREGATE PATTERNS ───────────────────────────────────────

    private static final Map<AggregateFunction, List<Pattern>> AGGREGATE_PATTERNS;
    static {
        AGGREGATE_PATTERNS = new LinkedHashMap<>(); // ordered — AVG checked before SUM
        AGGREGATE_PATTERNS.put(AggregateFunction.AVG, List.of(
                Pattern.compile("(?i)(average|avg|mean)"),
                Pattern.compile("(?i)average.*?of")
        ));
        AGGREGATE_PATTERNS.put(AggregateFunction.SUM, List.of(
                Pattern.compile("(?i)(sum|total amount|total value|add up)")
        ));
        AGGREGATE_PATTERNS.put(AggregateFunction.MAX, List.of(
                Pattern.compile("(?i)(maximum|max|highest|largest|biggest|most)")
        ));
        AGGREGATE_PATTERNS.put(AggregateFunction.MIN, List.of(
                Pattern.compile("(?i)(minimum|min|lowest|smallest|least)")
        ));
        AGGREGATE_PATTERNS.put(AggregateFunction.COUNT_DISTINCT, List.of(
                Pattern.compile("(?i)(count distinct|unique count|number of unique|how many unique|how many different)")
        ));
    }

    // ─── ENTITY EXTRACTION PATTERNS ───────────────────────────────

    // "...in employees table/file/sheet" or "...from payroll"
    private static final Pattern TABLE_HINT_PATTERN =
            Pattern.compile("(?i)(?:in|from|for|of)\\s+(\\w+)\\s*(?:table|file|sheet|dataset)?");

    // "by department" or "per department" or "grouped by department"
    private static final Pattern GROUP_BY_PATTERN =
            Pattern.compile("(?i)(?:by|per|grouped by|group by)\\s+(\\w+)");

    // LIMIT extraction: "first 10 rows" or "top 5"
    private static final Pattern LIMIT_PATTERN =
            Pattern.compile("(?i)(?:first|top|last|limit)\\s+(\\d+)");

    @Override
    public QueryIntent parse(String query, Collection<TableMetadata> availableTables) {
        if (StringUtils.isBlank(query)) {
            return unknownIntent(query, "Empty query.");
        }

        String normalized = query.trim();
        log.debug("Parsing intent for: '{}'", normalized);

        // Determine action (order matters — most specific first)
        IntentAction action;
        AggregateFunction aggFunc = null;
        double confidence = 0.5;

        if (matches(normalized, FIND_DUPLICATES_PATTERNS)) {
            action = IntentAction.FIND_DUPLICATES;
            confidence = 0.90;
        } else if (matches(normalized, SHOW_MISSING_PATTERNS)) {
            action = IntentAction.SHOW_MISSING_VALUES;
            confidence = 0.90;
        } else if (matches(normalized, FIND_FILES_PATTERNS)) {
            action = IntentAction.FIND_FILES_WITH_COLUMN;
            confidence = 0.85;
        } else if (matches(normalized, COUNT_ROWS_PATTERNS)) {
            action = IntentAction.COUNT_ROWS;
            confidence = 0.92;
        } else if (matches(normalized, SHOW_COLUMNS_PATTERNS)) {
            action = IntentAction.SHOW_COLUMNS;
            confidence = 0.90;
        } else if (matches(normalized, SHOW_DISTINCT_PATTERNS)) {
            action = IntentAction.SHOW_DISTINCT;
            confidence = 0.82;
        } else {
            // Check aggregate patterns
            Optional<Map.Entry<AggregateFunction, List<Pattern>>> aggMatch =
                    AGGREGATE_PATTERNS.entrySet().stream()
                            .filter(e -> matches(normalized, e.getValue()))
                            .findFirst();

            if (aggMatch.isPresent()) {
                action = IntentAction.AGGREGATE;
                aggFunc = aggMatch.get().getKey();
                confidence = 0.88;
            } else if (matches(normalized, SHOW_SAMPLE_PATTERNS)) {
                action = IntentAction.SHOW_SAMPLE;
                confidence = 0.75;
            } else {
                return unknownIntent(query, "Could not determine the intent of your query.");
            }
        }

        // Entity extraction
        String tableHint = extractTableHint(normalized, availableTables);
        String columnHint = extractColumnHint(normalized, action, availableTables);
        String groupByHint = extractGroupByHint(normalized);
        Integer limit = extractLimit(normalized);

        // Build interpretation summary (always shown to user for transparency)
        String summary = buildSummary(action, aggFunc, tableHint, columnHint, groupByHint);

        return QueryIntent.builder()
                .action(action)
                .targetTable(tableHint)
                .targetColumn(columnHint)
                .groupByColumn(groupByHint)
                .aggregateFunction(aggFunc)
                .limit(limit)
                .rawQuery(query)
                .confidence(confidence)
                .interpretationSummary(summary)
                .build();
    }

    // ─────────────────────────────────────────────────────────────
    // ENTITY EXTRACTION HELPERS
    // ─────────────────────────────────────────────────────────────

    private String extractTableHint(String query, Collection<TableMetadata> tables) {
        // First: try to match known table names directly from query
        String lower = query.toLowerCase();
        for (TableMetadata table : tables) {
            if (lower.contains(table.getTableName()) ||
                    lower.contains(table.getOriginalFileName().toLowerCase()
                            .replaceAll("\\.[^.]+$", ""))) {
                return table.getTableName();
            }
        }

        // Second: extract from "in X file/table" pattern
        Matcher m = TABLE_HINT_PATTERN.matcher(query);
        if (m.find()) {
            return m.group(1).toLowerCase();
        }

        return null; // IntentEnrichmentService will try to resolve
    }

    /**
     * Extract column hint from query.
     *
     * Strategy: after removing action keywords and table references,
     * the remaining nouns are candidate column names.
     * We match them against known column names in available tables.
     */
    private String extractColumnHint(String query, IntentAction action,
                                      Collection<TableMetadata> tables) {
        String lower = query.toLowerCase();

        // Remove common action words to isolate the column name
        String stripped = lower
                .replaceAll("(?i)(find|show|display|list|get|count|calculate|compute)", "")
                .replaceAll("(?i)(duplicate|missing|null|empty|blank|average|avg|sum|max|min|unique|distinct)", "")
                .replaceAll("(?i)(in|from|for|of|the|a|an|rows?|column|field|file|table|sheet|data|values?)", "")
                .replaceAll("\\s+", " ").trim();

        // Remove group-by part ("by X")
        stripped = stripped.replaceAll("(?i)\\bby\\s+\\w+", "").trim();

        // The remaining significant word is likely the column name
        String[] tokens = stripped.split("\\s+");
        for (String token : tokens) {
            if (token.length() > 1) {
                // Verify this token matches a known column
                for (TableMetadata table : tables) {
                    if (table.hasColumn(token)) {
                        return token;
                    }
                }
                // Return as hint even if not verified — enrichment will validate
                if (token.length() > 2) return token;
            }
        }

        return null;
    }

    private String extractGroupByHint(String query) {
        Matcher m = GROUP_BY_PATTERN.matcher(query);
        if (m.find()) {
            return m.group(1).toLowerCase();
        }
        return null;
    }

    private Integer extractLimit(String query) {
        Matcher m = LIMIT_PATTERN.matcher(query);
        if (m.find()) {
            try {
                return Integer.parseInt(m.group(1));
            } catch (NumberFormatException ignored) {}
        }
        return null;
    }

    private boolean matches(String query, List<Pattern> patterns) {
        return patterns.stream().anyMatch(p -> p.matcher(query).find());
    }

    private String buildSummary(IntentAction action, AggregateFunction aggFunc,
                                 String table, String column, String groupBy) {
        StringBuilder sb = new StringBuilder("Understood: ");
        switch (action) {
            case COUNT_ROWS:
                sb.append("Count total rows").append(table != null ? " in table '" + table + "'" : "");
                break;
            case SHOW_COLUMNS:
                sb.append("Show column names and types").append(table != null ? " for table '" + table + "'" : "");
                break;
            case FIND_DUPLICATES:
                sb.append("Find duplicate values");
                if (column != null) sb.append(" in column '").append(column).append("'");
                if (table != null) sb.append(" from table '").append(table).append("'");
                break;
            case SHOW_MISSING_VALUES:
                sb.append("Show missing/null values");
                if (column != null) sb.append(" in column '").append(column).append("'");
                if (table != null) sb.append(" from table '").append(table).append("'");
                break;
            case AGGREGATE:
                sb.append("Calculate ").append(aggFunc).append(" of '").append(column).append("'");
                if (groupBy != null) sb.append(" grouped by '").append(groupBy).append("'");
                if (table != null) sb.append(" from table '").append(table).append("'");
                break;
            case FIND_FILES_WITH_COLUMN:
                sb.append("Find all files containing column '").append(column).append("'");
                break;
            case SHOW_DISTINCT:
                sb.append("Show distinct values in column '").append(column).append("'");
                if (table != null) sb.append(" from table '").append(table).append("'");
                break;
            case SHOW_SAMPLE:
                sb.append("Show sample rows").append(table != null ? " from table '" + table + "'" : "");
                break;
            default:
                sb.append("Intent unclear.");
        }
        return sb.toString();
    }

    private QueryIntent unknownIntent(String query, String reason) {
        return QueryIntent.builder()
                .action(IntentAction.UNKNOWN)
                .rawQuery(query)
                .confidence(0.0)
                .interpretationSummary("Could not parse intent: " + reason +
                        " Try: 'count rows in employees', 'average salary by department'")
                .build();
    }
}