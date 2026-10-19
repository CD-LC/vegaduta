package com.agenticai.core.graphmemory;

/**
 * One extracted (subject, relation, object) fact - e.g. {@code ("Priya",
 * "MANAGER_OF_BY", "Raj")} from "Priya's manager is Raj". See {@link
 * EntityExtractionService}.
 */
public record GraphTriple(String subject, String relation, String object) {
}
