package com.enterprise.dataanalyst.service.llm;

import ai.onnxruntime.OrtException;
import com.enterprise.dataanalyst.model.TableMetadata;
import com.enterprise.dataanalyst.nlp.AggregateFunction;
import com.enterprise.dataanalyst.nlp.IntentAction;
import com.enterprise.dataanalyst.nlp.QueryIntent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * SemanticIntentClassifier
 *
 * Classifies a natural language query into a structured {@link QueryIntent}
 * using MiniLM (all-MiniLM-L6-v2) sentence embeddings via ONNX Runtime.
 *
 * ─── HOW IT WORKS ────────────────────────────────────────────────
 *
 * STEP 1 — TEMPLATE EMBEDDINGS (startup, one-time):
 *   For each supported IntentAction, we define a list of representative
 *   English phrases (templates). At startup, every template is passed
 *   through the MiniLM transformer to produce a 384-dimensional float
 *   vector. These vectors are stored in memory.
 *
 * STEP 2 — QUERY EMBEDDING (per request, ~30-80ms):
 *   The user's query is passed through the same transformer to get
 *   its own 384-dim vector.
 *
 * STEP 3 — COSINE SIMILARITY:
 *   We compute the dot product of the query vector against every
 *   template vector (both are L2-normalized, so dot product = cosine
 *   similarity). The intent whose template has the highest similarity
 *   to the query is selected.
 *
 * STEP 4 — ENTITY EXTRACTION:
 *   Table name and column name are extracted using lightweight regex
 *   and validated against the TableMetadataRegistry. AI handles
 *   semantic intent; heuristics handle domain-specific entity names
 *   (column names, table names) that the AI cannot know.
 *
 * ─── WHY NO HALLUCINATION ────────────────────────────────────────
 *   The model NEVER generates text. It only produces a float vector.
 *   The output is always one of our fixed IntentAction enum values.
 *   SQL is generated deterministically by IntentBasedSQLGenerator.
 *   Actual data never touches the model — only DDL schema is passed
 *   for entity resolution context.
 *
 * ─── JAVA 11 COMPATIBILITY ───────────────────────────────────────
 *   No switch expressions, no text blocks, no var, no List.of()
 *   with more than 10 elements. Uses Arrays.asList() throughout.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SemanticIntentClassifier {

    private final EmbeddingService embeddingService;

    @Value("${app.llm.confidence-threshold:0.45}")
    private float confidenceThreshold;

    /**
     * In-memory store of pre-computed template embeddings.
     * Key   = IntentAction
     * Value = list of 384-dim float vectors (one per template phrase)
     *
     * Populated at startup via {@link #precomputeEmbeddings()}.
     * Read-only after startup — thread safe for concurrent queries.
     */
    private final Map<IntentAction, List<float[]>> intentEmbeddings = new LinkedHashMap<>();

    // ─────────────────────────────────────────────────────────────
    // INTENT TEMPLATES
    //
    // WHY MULTIPLE TEMPLATES PER INTENT:
    // "count rows in employees" and "pull headcount from the file"
    // mean the same thing but share zero keywords.
    // Multiple phrasings give the model better coverage of how
    // real users phrase the same intent.
    //
    // HOW TO EXTEND:
    // Add more phrases to any list below if you find queries being
    // misclassified. More diverse phrases = better accuracy.
    // ─────────────────────────────────────────────────────────────
    private static final Map<IntentAction, List<String>> INTENT_TEMPLATES;
    static {
        INTENT_TEMPLATES = new LinkedHashMap<>();

        INTENT_TEMPLATES.put(IntentAction.COUNT_ROWS, Arrays.asList(
                "count the number of rows in the table",
                "how many rows are there in the file",
                "total number of records in this dataset",
                "how many entries exist",
                "give me the row count",
                "how many records are present",
                "total rows in the table",
                "how many people are in this file",
                "pull headcount from the table",
                "how many items are in the dataset"
        ));

        INTENT_TEMPLATES.put(IntentAction.SHOW_COLUMNS, Arrays.asList(
                "show me all column names in the table",
                "what fields does this table have",
                "list all columns available in the file",
                "describe the schema of this table",
                "what headers are available in this dataset",
                "show the structure of the data",
                "which columns exist in this dataset",
                "what are the available fields",
                "show me the column names and their types"
        ));

        INTENT_TEMPLATES.put(IntentAction.SHOW_SAMPLE, Arrays.asList(
                "show me some rows from the table",
                "preview the data in this file",
                "display the first few records",
                "give me a sample of the data",
                "show top 10 rows from the table",
                "let me see the data",
                "fetch some records from the file",
                "display a few rows of data"
        ));

        INTENT_TEMPLATES.put(IntentAction.FIND_DUPLICATES, Arrays.asList(
                "find duplicate values in this column",
                "show me repeated entries in the field",
                "detect duplicates in this column",
                "which values appear more than once",
                "find repeated employee ids",
                "show duplicated records in the table",
                "identify duplicate rows by column"
        ));

        INTENT_TEMPLATES.put(IntentAction.SHOW_MISSING_VALUES, Arrays.asList(
                "show missing values in the column",
                "which rows have null values in this field",
                "find empty fields in the data",
                "count blank entries in the column",
                "where is data missing in the table",
                "how many nulls are in this column",
                "find records with no value in this field",
                "how many values are empty or blank"
        ));

        INTENT_TEMPLATES.put(IntentAction.SHOW_DISTINCT, Arrays.asList(
                "show distinct values in this column",
                "list all unique values in the field",
                "what are the different values in this column",
                "show all possible values for this field",
                "unique entries in this column",
                "what categories exist in this field",
                "show different status values"
        ));

        INTENT_TEMPLATES.put(IntentAction.FIND_FILES_WITH_COLUMN, Arrays.asList(
                "which files contain this column",
                "what tables have this field",
                "find files that have this column name",
                "which datasets contain this field",
                "what files have a column named email"
        ));

        INTENT_TEMPLATES.put(IntentAction.AGGREGATE, Arrays.asList(
                "calculate the average salary by department",
                "mean value grouped by category",
                "sum of revenue grouped by region",
                "maximum salary in each department",
                "minimum age per group",
                "total sales grouped by month",
                "compute average of a column grouped by another",
                "what is the sum of amount by category",
                "average pay grouped by cost center",
                "total amount per department"
        ));
    }

    /**
     * Aggregate function sub-classification keywords.
     *
     * WHY KEYWORD MATCH HERE (not AI):
     * We already know the intent is AGGREGATE from semantic similarity.
     * We only need to determine WHICH aggregate function (AVG/SUM/MAX/MIN).
     * Simple keyword match is sufficient and 100% reliable for these
     * well-defined mathematical terms.
     *
     * ORDER MATTERS — "count distinct" must appear before "count"
     * to avoid partial match.
     */
    private static final Map<String, AggregateFunction> AGG_KEYWORDS;
    static {
        AGG_KEYWORDS = new LinkedHashMap<>();
        AGG_KEYWORDS.put("count distinct",  AggregateFunction.COUNT_DISTINCT);
        AGG_KEYWORDS.put("how many unique", AggregateFunction.COUNT_DISTINCT);
        AGG_KEYWORDS.put("number of unique",AggregateFunction.COUNT_DISTINCT);
        AGG_KEYWORDS.put("average",         AggregateFunction.AVG);
        AGG_KEYWORDS.put("avg",             AggregateFunction.AVG);
        AGG_KEYWORDS.put("mean",            AggregateFunction.AVG);
        AGG_KEYWORDS.put("sum",             AggregateFunction.SUM);
        AGG_KEYWORDS.put("total",           AggregateFunction.SUM);
        AGG_KEYWORDS.put("maximum",         AggregateFunction.MAX);
        AGG_KEYWORDS.put("max",             AggregateFunction.MAX);
        AGG_KEYWORDS.put("highest",         AggregateFunction.MAX);
        AGG_KEYWORDS.put("largest",         AggregateFunction.MAX);
        AGG_KEYWORDS.put("minimum",         AggregateFunction.MIN);
        AGG_KEYWORDS.put("min",             AggregateFunction.MIN);
        AGG_KEYWORDS.put("lowest",          AggregateFunction.MIN);
        AGG_KEYWORDS.put("smallest",        AggregateFunction.MIN);
    }

    // ─────────────────────────────────────────────────────────────
    // STARTUP — PRE-COMPUTE TEMPLATE EMBEDDINGS
    // ─────────────────────────────────────────────────────────────

    /**
     * Pre-computes embeddings for all intent templates at application startup.
     *
     * WHY AT STARTUP:
     * Computing an embedding takes ~30-80ms on CPU. We have ~70 templates.
     * Doing this at startup costs ~3-5 seconds once.
     * After this, per-query cost = just dot products (~1ms).
     *
     * If any template fails to embed (model error), we log a warning
     * and continue — partial coverage is better than total failure.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void precomputeEmbeddings() {
        log.info("Pre-computing intent embeddings — this takes ~3-5 seconds...");
        long start = System.currentTimeMillis();
        int total = 0;

        for (Map.Entry<IntentAction, List<String>> entry : INTENT_TEMPLATES.entrySet()) {
            List<float[]> embeddings = new ArrayList<>();

            for (String template : entry.getValue()) {
                try {
                    float[] embedding = embeddingService.embed(template);
                    embeddings.add(embedding);
                    total++;
                } catch (OrtException e) {
                    log.warn("Failed to embed template '{}' for intent {}: {}",
                            template, entry.getKey(), e.getMessage());
                }
            }

            intentEmbeddings.put(entry.getKey(), embeddings);
        }

        long elapsed = System.currentTimeMillis() - start;
        log.info("Intent embeddings ready: {} templates across {} intents in {}ms.",
                total, intentEmbeddings.size(), elapsed);
    }

    // ─────────────────────────────────────────────────────────────
    // PRIMARY API
    // ─────────────────────────────────────────────────────────────

    /**
     * Classifies a natural language query into a structured {@link QueryIntent}.
     *
     * @param query           Natural language input from the user
     * @param availableTables All currently registered tables (for entity resolution)
     * @return Populated QueryIntent ready for SQL generation
     */
    public QueryIntent classify(String query, Collection<TableMetadata> availableTables) {
        if (query == null || query.trim().isEmpty()) {
            return unknownIntent(query, "Empty query received.");
        }

        // Guard: embeddings must be ready
        if (intentEmbeddings.isEmpty()) {
            log.warn("Intent embeddings not yet ready. Is startup complete?");
            return unknownIntent(query, "AI model is still initializing. Please wait.");
        }

        try {
            // ── STEP 1: Embed the user query ──────────────────────
            float[] queryEmbedding = embeddingService.embed(query);

            // ── STEP 2: Find best matching intent ─────────────────
            // For each intent, find the maximum similarity across all its templates.
            // The intent with the highest max-similarity wins.
            IntentAction bestAction = null;
            float bestScore = -1f;

            for (Map.Entry<IntentAction, List<float[]>> entry : intentEmbeddings.entrySet()) {
                float maxScoreForIntent = -1f;

                for (float[] templateEmbedding : entry.getValue()) {
                    float score = embeddingService.cosineSimilarity(
                            queryEmbedding, templateEmbedding);
                    if (score > maxScoreForIntent) {
                        maxScoreForIntent = score;
                    }
                }

                log.debug("Intent {} → best template score: {}", entry.getKey(), maxScoreForIntent);

                if (maxScoreForIntent > bestScore) {
                    bestScore = maxScoreForIntent;
                    bestAction = entry.getKey();
                }
            }

            log.info("Classified '{}' → {} (raw score: {})", query, bestAction, bestScore);

            // ── STEP 3: Confidence gate ───────────────────────────
            // If even the best match is below threshold, the query is
            // too ambiguous to act on safely. Ask user to rephrase.
            if (bestScore < confidenceThreshold) {
                return unknownIntent(query,
                        "Query not understood with sufficient confidence " +
                                "(score: " + String.format("%.2f", bestScore) + "). " +
                                "Try: 'average salary by department' or 'count rows in employees'.");
            }

            // ── STEP 4: Aggregate function detection ──────────────
            AggregateFunction aggFunc = null;
            if (bestAction == IntentAction.AGGREGATE) {
                aggFunc = detectAggregateFunction(query.toLowerCase());
                if (aggFunc == null) {
                    // Default to AVG — most common aggregate intent
                    aggFunc = AggregateFunction.AVG;
                    log.debug("Aggregate function not detected explicitly. Defaulting to AVG.");
                }
            }

            // ── STEP 5: Entity extraction ─────────────────────────
            // AI handled WHAT the user wants.
            // Now extract WHERE (table) and ON WHAT (column) using
            // lightweight heuristics + metadata validation.
            String tableHint  = extractTableHint(query, availableTables);
            String columnHint = extractColumnHint(query, bestAction, availableTables);
            String groupBy    = extractGroupByHint(query);
            Integer limit     = extractLimit(query);

            double confidence = normalizeScore(bestScore);
            String summary    = buildSummary(bestAction, aggFunc, tableHint, columnHint, groupBy);

            return QueryIntent.builder()
                    .action(bestAction)
                    .targetTable(tableHint)
                    .targetColumn(columnHint)
                    .groupByColumn(groupBy)
                    .aggregateFunction(aggFunc)
                    .limit(limit)
                    .rawQuery(query)
                    .confidence(confidence)
                    .interpretationSummary(summary)
                    .build();

        } catch (OrtException e) {
            log.error("ONNX inference error during classification: {}", e.getMessage(), e);
            return unknownIntent(query,
                    "AI model encountered an error. Please try again. Details: " + e.getMessage());
        }
    }

    // ─────────────────────────────────────────────────────────────
    // ENTITY EXTRACTION
    //
    // These methods resolve table and column names from the raw query
    // text, validated against actual TableMetadata in the registry.
    //
    // Priority order:
    // 1. Direct substring match of known metadata names in query
    // 2. Pattern match ("in X", "from X", "by X")
    // 3. Return null — IntentEnrichmentService or SQLGenerator will
    //    handle the missing entity with a clear error message.
    // ─────────────────────────────────────────────────────────────

    /**
     * Resolves the target table name from the user query.
     *
     * Strategy:
     * 1. Check if any known table name appears directly in the query.
     * 2. Check if the original file name (without extension) appears.
     * 3. Parse "in X", "from X", "for X" pattern.
     */
    private String extractTableHint(String query, Collection<TableMetadata> tables) {
        String lower = query.toLowerCase();

        // Priority 1: direct match of known table/file name
        for (TableMetadata table : tables) {
            if (lower.contains(table.getTableName())) {
                log.debug("Table resolved by direct match: '{}'", table.getTableName());
                return table.getTableName();
            }
            // Match without file extension: "employees.csv" → "employees"
            String fileBase = table.getOriginalFileName()
                    .replaceAll("\\.[^.]+$", "")
                    .toLowerCase();
            if (lower.contains(fileBase)) {
                log.debug("Table resolved by filename: '{}'", table.getTableName());
                return table.getTableName();
            }
        }

        // Priority 2: pattern match
        Matcher m = Pattern.compile(
                        "(?i)(?:in|from|for|of)\\s+(\\w+)(?:\\s+(?:file|table|sheet|dataset))?")
                .matcher(query);
        if (m.find()) {
            String candidate = m.group(1).toLowerCase();
            log.debug("Table hint from pattern: '{}'", candidate);
            return candidate;
        }

        // Not found — SQLGenerator will produce a clear error message
        return null;
    }

    /**
     * Resolves the target column name from the user query.
     *
     * Strategy:
     * 1. For FIND_FILES_WITH_COLUMN: extract noun after "contain/have/with"
     * 2. Match any known column name from loaded tables
     * 3. Extract noun from "in X column", "of X field" pattern
     */
    private String extractColumnHint(String query, IntentAction action,
                                     Collection<TableMetadata> tables) {
        String lower = query.toLowerCase();

        // Special case: "which files have email column" → extract "email"
        if (action == IntentAction.FIND_FILES_WITH_COLUMN) {
            Matcher m = Pattern.compile(
                    "(?i)(?:contain|have|with|column|field)\\s+(\\w+)").matcher(query);
            if (m.find()) {
                return m.group(1).toLowerCase();
            }
        }

        // Priority 1: match against actual known column names in any loaded table
        // This is the most reliable resolution — validated against real schema
        for (TableMetadata table : tables) {
            for (var col : table.getColumns()) {
                if (lower.contains(col.getColumnName().toLowerCase()) ||
                        lower.contains(col.getOriginalName().toLowerCase())) {
                    log.debug("Column resolved from metadata: '{}'", col.getColumnName());
                    return col.getColumnName();
                }
            }
        }

        // Priority 2: pattern match — noun after action-related prepositions
        // Covers: "missing values in phone", "duplicates in employee_id"
        Matcher m = Pattern.compile(
                "(?i)(?:in|of|for|on)\\s+(\\w+)(?:\\s+(?:column|field|col))?").matcher(query);
        if (m.find()) {
            String candidate = m.group(1).toLowerCase();
            // Filter structural stop words
            if (!isStructuralWord(candidate)) {
                log.debug("Column hint from pattern: '{}'", candidate);
                return candidate;
            }
        }

        return null;
    }

    /**
     * Extracts the GROUP BY column from the query.
     *
     * Handles:
     * - "by department"
     * - "per region"
     * - "grouped by cost_center"
     * - "group by category"
     */
    private String extractGroupByHint(String query) {
        Matcher m = Pattern.compile(
                "(?i)(?:grouped by|group by|by|per)\\s+(\\w+)").matcher(query);
        if (m.find()) {
            String candidate = m.group(1).toLowerCase();
            // "by the", "by a" etc — skip articles
            if (!isStructuralWord(candidate)) {
                log.debug("Group-by hint: '{}'", candidate);
                return candidate;
            }
        }
        return null;
    }

    /**
     * Extracts row limit from queries like:
     * "show first 20 rows", "top 5 records", "limit 50"
     */
    private Integer extractLimit(String query) {
        Matcher m = Pattern.compile(
                "(?i)(?:first|top|last|limit)\\s+(\\d+)").matcher(query);
        if (m.find()) {
            try {
                return Integer.parseInt(m.group(1));
            } catch (NumberFormatException ignored) {
                // Malformed number — ignore and use default
            }
        }
        return null;
    }

    // ─────────────────────────────────────────────────────────────
    // AGGREGATE FUNCTION DETECTION
    // ─────────────────────────────────────────────────────────────

    private AggregateFunction detectAggregateFunction(String lower) {
        for (Map.Entry<String, AggregateFunction> entry : AGG_KEYWORDS.entrySet()) {
            if (lower.contains(entry.getKey())) {
                return entry.getValue();
            }
        }
        return null;
    }

    // ─────────────────────────────────────────────────────────────
    // HELPERS
    // ─────────────────────────────────────────────────────────────

    /**
     * Words that are structural (prepositions, articles, query keywords)
     * and should not be treated as entity names.
     */
    private boolean isStructuralWord(String word) {
        List<String> stopWords = Arrays.asList(
                "the", "a", "an", "this", "that", "all", "each", "every",
                "rows", "row", "record", "records", "data", "file", "files",
                "table", "tables", "sheet", "column", "columns", "field",
                "value", "values", "result", "results", "entry", "entries"
        );
        return stopWords.contains(word);
    }

    /**
     * Normalizes raw cosine similarity score [0.3, 1.0] to
     * user-friendly confidence [0.0, 1.0].
     *
     * WHY THIS RANGE:
     * Even unrelated sentences have cosine similarity ~0.2-0.3
     * due to shared semantic space. We treat 0.3 as the noise floor.
     */
    private double normalizeScore(float score) {
        double normalized = (score - 0.3) / (1.0 - 0.3);
        return Math.min(1.0, Math.max(0.0, normalized));
    }

    /**
     * Builds a human-readable summary of what the system understood.
     * Always shown to the user in the UI for transparency and correction.
     */
    private String buildSummary(IntentAction action, AggregateFunction aggFunc,
                                String table, String column, String groupBy) {
        StringBuilder sb = new StringBuilder("Understood: ");

        if (action == IntentAction.COUNT_ROWS) {
            sb.append("Count total rows");
            if (table != null) sb.append(" in '").append(table).append("'");

        } else if (action == IntentAction.SHOW_COLUMNS) {
            sb.append("Show column schema");
            if (table != null) sb.append(" of '").append(table).append("'");

        } else if (action == IntentAction.SHOW_SAMPLE) {
            sb.append("Show sample rows");
            if (table != null) sb.append(" from '").append(table).append("'");

        } else if (action == IntentAction.FIND_DUPLICATES) {
            sb.append("Find duplicates");
            if (column != null) sb.append(" in column '").append(column).append("'");
            if (table != null) sb.append(" from '").append(table).append("'");

        } else if (action == IntentAction.SHOW_MISSING_VALUES) {
            sb.append("Show missing/null values");
            if (column != null) sb.append(" in column '").append(column).append("'");
            if (table != null) sb.append(" from '").append(table).append("'");

        } else if (action == IntentAction.AGGREGATE) {
            sb.append(aggFunc != null ? aggFunc.name() : "AGGREGATE");
            if (column != null) sb.append(" of '").append(column).append("'");
            if (groupBy != null) sb.append(" grouped by '").append(groupBy).append("'");
            if (table != null) sb.append(" from '").append(table).append("'");

        } else if (action == IntentAction.SHOW_DISTINCT) {
            sb.append("Show distinct values");
            if (column != null) sb.append(" in '").append(column).append("'");
            if (table != null) sb.append(" from '").append(table).append("'");

        } else if (action == IntentAction.FIND_FILES_WITH_COLUMN) {
            sb.append("Find all files containing column '");
            sb.append(column != null ? column : "?").append("'");

        } else {
            sb.append("Intent unclear.");
        }

        return sb.toString();
    }

    private QueryIntent unknownIntent(String query, String reason) {
        return QueryIntent.builder()
                .action(IntentAction.UNKNOWN)
                .rawQuery(query)
                .confidence(0.0)
                .interpretationSummary("Could not understand query: " + reason)
                .build();
    }
}