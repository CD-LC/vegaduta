package com.agenticai.core.graphmemory;

import com.agenticai.core.screen.ContentWriteScreen;

/**
 * One extracted (subject, relation, object) fact - e.g. {@code ("Priya",
 * "MANAGER_OF_BY", "Raj")} from "Priya's manager is Raj". See {@link
 * EntityExtractionService}.
 */
public record GraphTriple(String subject, String relation, String object) {

    /**
     * True when any part of the triple trips the shared write screen ({@link ContentWriteScreen}:
     * an instruction-shaped phrase or a credential). {@link GraphMemoryService} drops such a triple
     * instead of writing it - graph facts are recalled into later turns, possibly for other
     * agents, so a directive or key stored as an entity name outlives the turn that wrote it.
     */
    public boolean tripsWriteScreen() {
        return ContentWriteScreen.refusal(subject + "\n" + relation + "\n" + object).isPresent();
    }
}
