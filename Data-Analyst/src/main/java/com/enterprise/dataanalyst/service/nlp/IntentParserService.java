// service/nlp/IntentParserService.java
package com.enterprise.dataanalyst.service.nlp;

import com.enterprise.dataanalyst.nlp.QueryIntent;
import com.enterprise.dataanalyst.model.TableMetadata;

import java.util.Collection;

/**
 * Contract for natural language → structured intent conversion.
 *
 * WHY AN INTERFACE:
 * The current implementation is rule-based (RuleBasedIntentParser).
 * When a local LLM (Ollama + Mistral/Llama) becomes available, we create
 * LocalLLMIntentParser implementing this same interface.
 * Zero changes to SQLGeneratorService or any downstream code.
 *
 * DESIGN PRINCIPLE — AI ONLY FOR INTENT:
 * The implementation must ONLY populate QueryIntent fields.
 * It must NEVER compute answers from data. That is the SQL engine's job.
 */
public interface IntentParserService {

    /**
     * Parse a natural language query into a structured QueryIntent.
     *
     * @param query           The user's English question
     * @param availableTables All currently loaded table schemas (for entity resolution)
     * @return QueryIntent with action, table hints, column hints, and confidence score
     */
    QueryIntent parse(String query, Collection<TableMetadata> availableTables);
}