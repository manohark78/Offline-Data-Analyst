/**
 * Validates schema and enriches prompt with actual values.
 *
 * CRITICAL FOR VALUE MISMATCH:
 * User: "females in Mumbai"
 * → Detects "gender" and "city" filter columns
 * → Queries: SELECT DISTINCT gender, SELECT DISTINCT city
 * → Finds actual values: ['M', 'F'], ['Mumbai', 'Delhi', 'Bangalore']
 * → Enriches prompt:
 *   "gender column has values: M, F (use 'F' for female)"
 *   "city column has values: Mumbai, Delhi, Bangalore"
 * → SQLCoder now generates: WHERE gender = 'F' AND city = 'Mumbai' ✅
 */
public class QueryEnricher {

    private final TableMetadataRegistry registry;
    private final DuckDBStorageService storage;

    /**
     * Enrich query context before sending to LLM.
     */
    public EnrichedContext enrich(String userQuery,
                                   String resolvedTable) {
        EnrichedContext ctx = new EnrichedContext();
        ctx.userQuery = userQuery;
        ctx.tableName = resolvedTable;

        if (resolvedTable == null) {
            ctx.issues.add("No table resolved from query");
            return ctx;
        }

        Optional<TableMetadata> meta =
                registry.findByTableName(resolvedTable);
        if (meta.isEmpty()) {
            ctx.issues.add("Table not found: " + resolvedTable);
            return ctx;
        }

        TableMetadata table = meta.get();
        String lower = userQuery.toLowerCase();

        // Extract mentioned columns
        List<String> mentionedCols =
                extractMentionedColumns(lower, table);
        ctx.mentionedColumns.addAll(mentionedCols);

        // Check for non-existent columns
        for (String colQuery : mentionedCols) {
            String resolved = table.resolveColumn(colQuery);
            if (resolved == null) {
                ctx.issues.add(
                    "Column '" + colQuery + "' not found. " +
                    "Available: " + table.getColumns().stream()
                        .map(ColumnMetadata::getColumnName)
                        .collect(Collectors.joining(", "))
                );
            }
        }

        // If issues found → stop here
        if (!ctx.issues.isEmpty()) return ctx;

        // Enrich with actual values for filter columns
        ctx.valueHints.putAll(
            buildValueHints(lower, table, resolvedTable));

        return ctx;
    }

    /**
     * Build value hints for common filter columns.
     *
     * Detects filters: gender, payment, city, status, etc.
     * Queries actual distinct values from DuckDB.
     */
    private Map<String, String> buildValueHints(
            String lower, TableMetadata table, String tableName) {

        Map<String, String> hints = new LinkedHashMap<>();

        // Gender filters
        if (lower.contains("female") || lower.contains("male") ||
            lower.contains("gender")) {
            String genderCol = findColumn(table,
                "gender", "sex", "m_f");
            if (genderCol != null) {
                hints.put("gender",
                    queryDistinctValues(tableName, genderCol, 10));
            }
        }

        // Payment filters
        if (lower.contains("upi") || lower.contains("payment") ||
            lower.contains("card") || lower.contains("cash")) {
            String pmtCol = findColumn(table,
                "payment", "method", "mode", "type");
            if (pmtCol != null) {
                hints.put("payment_method",
                    queryDistinctValues(tableName, pmtCol, 10));
            }
        }

        // Location filters
        if (lower.contains("mumbai") || lower.contains("delhi") ||
            lower.contains("city") || lower.contains("region")) {
            String cityCol = findColumn(table,
                "city", "location", "region", "zone");
            if (cityCol != null) {
                hints.put("city",
                    queryDistinctValues(tableName, cityCol, 20));
            }
        }

        // Status filters
        if (lower.contains("status") || lower.contains("active") ||
            lower.contains("pending") || lower.contains("complete")) {
            String statusCol = findColumn(table,
                "status", "state", "condition");
            if (statusCol != null) {
                hints.put("status",
                    queryDistinctValues(tableName, statusCol, 10));
            }
        }

        return hints;
    }

    /**
     * Query actual distinct values from DuckDB.
     */
    private String queryDistinctValues(String table,
                                        String column, int limit) {
        try {
            String sql = String.format(
                "SELECT DISTINCT \"%s\" FROM \"%s\" " +
                "WHERE \"%s\" IS NOT NULL LIMIT %d",
                column, table, column, limit);

            List<Map<String, Object>> results =
                    storage.executeQuery(sql);

            return results.stream()
                    .map(r -> String.valueOf(r.get(column)))
                    .collect(Collectors.joining(", "));
        } catch (Exception e) {
            return "(query failed)";
        }
    }

    private String findColumn(TableMetadata table,
                               String... keywords) {
        for (String kw : keywords) {
            String found = table.resolveColumn(kw);
            if (found != null) return found;
        }
        return null;
    }

    // ... extractMentionedColumns implementation
}

class EnrichedContext {
    String userQuery;
    String tableName;
    List<String> mentionedColumns = new ArrayList<>();
    Map<String, String> valueHints = new LinkedHashMap<>();
    List<String> issues = new ArrayList<>();

    boolean hasIssues() { return !issues.isEmpty(); }
}
