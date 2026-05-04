package com.enterprise.dataanalyst.service.intent

/**
 * First filter — routes non-data queries away from SQL pipeline.
 * FAST — rule-based pattern matching, no ML model.
 */
public class IntentRouter {

    public enum Intent {
        GREETING,      // hi, hello, hey
        HELP,          // what can you do, help me
        SCHEMA_QUERY,  // show columns, list tables (deterministic)
        EXPLANATION,   // how does this work, what is this
        DATA_QUERY     // actual data analysis queries
    }

    public Intent classify(String query) {
        String lower = query.toLowerCase().trim();

        // Greetings
        if (matches(lower,
            "^(hi|hello|hey|good morning|good evening).*")) {
            return Intent.GREETING;
        }

        // Help
        if (matches(lower,
            ".*(help|what can|how do i|guide|tutorial).*")) {
            return Intent.HELP;
        }

        // Schema queries (already handled in tryDeterministic)
        if (matches(lower,
            ".*(show column|list table|describe|schema).*")) {
            return Intent.SCHEMA_QUERY;
        }

        // Explanations
        if (matches(lower,
            ".*(how does|explain|what is this|tell me about).*")) {
            return Intent.EXPLANATION;
        }

        // Default: data query
        return Intent.DATA_QUERY;
    }

    private boolean matches(String text, String pattern) {
        return text.matches(pattern);
    }

    public String respondToGreeting() {
        return "Hello! I'm your offline data analyst. " +
               "Upload a file and ask me questions about your data.";
    }

    public String respondToHelp() {
        return "I can help you analyze CSV and Excel files. " +
               "Try:\n" +
               "• 'Show columns in [table]'\n" +
               "• 'Count rows'\n" +
               "• 'Average [column] by [group]'\n" +
               "• 'Find duplicates in [column]'\n" +
               "Upload a file to get started!";
    }

    public String respondToExplanation(String query) {
        return "I'm an offline data analyst — your data stays " +
               "on your machine. I use a local AI model to understand " +
               "your questions and generate SQL queries.\n\n" +
               "Ask me specific questions about your uploaded data!";
    }
}
