package com.agenticai.core.knowledge;

import com.agenticai.core.screen.ContentWriteScreen;
import java.util.Optional;

/**
 * Write-time screen for text an AGENT is about to persist into a knowledge collection ({@code
 * add_to_knowledge_base}). The pattern lists live in the shared {@link ContentWriteScreen} (also
 * used by {@code remember_fact} and graph-memory writes); this wrapper keeps the knowledge
 * module's call sites.
 *
 * <p>Agent-authored notes are refused on any hit ({@link #refusal}). Ingestion paths (upload, URL
 * ingest, connectors, ingestion jobs) go through {@link KnowledgeIngestionService}, which does not
 * refuse a whole document on an injection regex hit - a long document that quotes a phrase is far
 * more often benign - but records a {@code screen_flags} marker on each affected chunk that search
 * results surface to the model. Credentials are refused on every path.
 */
final class KnowledgeWriteScreen {

    private KnowledgeWriteScreen() {}

    /** A caller-safe reason the text must not be stored, or empty when it passes. */
    static Optional<String> refusal(String content) {
        return ContentWriteScreen.refusal(content);
    }
}
