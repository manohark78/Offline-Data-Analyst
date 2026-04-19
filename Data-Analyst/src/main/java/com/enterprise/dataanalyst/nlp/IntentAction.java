// nlp/IntentAction.java
package com.enterprise.dataanalyst.nlp;

/**
 * The set of structured actions we can extract from natural language.
 *
 * DESIGN DECISION:
 * We use an enum — not free-form strings — for the action type.
 * This makes SQLGeneratorService a clean switch statement with no
 * string comparison bugs. Every supported query type is explicit here.
 *
 * EXTENSIBILITY: Add new actions here + add case in SQLGeneratorService.
 */
public enum IntentAction {
    COUNT_ROWS,           // "count rows", "how many rows"
    SHOW_COLUMNS,         // "show columns", "what columns", "schema"
    SHOW_SAMPLE,          // "show me data", "first 10 rows", "preview"
    FIND_DUPLICATES,      // "find duplicates in column X"
    SHOW_MISSING_VALUES,  // "missing values", "nulls", "empty fields"
    AGGREGATE,            // "average X by Y", "sum of X", "max salary"
    SHOW_DISTINCT,        // "unique values in column X"
    FIND_FILES_WITH_COLUMN, // "which files have email column"
    UNKNOWN               // below confidence threshold — ask for clarification
}