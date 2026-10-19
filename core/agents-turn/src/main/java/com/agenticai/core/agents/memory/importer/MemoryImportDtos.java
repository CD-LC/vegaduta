package com.agenticai.core.agents.memory.importer;

import java.util.List;
import java.util.UUID;

/** Request/response bodies of {@code /api/memory/import} - see {@link MemoryImportService}. */
public final class MemoryImportDtos {

    private MemoryImportDtos() {
    }

    /** {@code sourceHint}: chatgpt | claude | gemini | other. Only labels the import; it does not change parsing. */
    public record PreviewRequest(String text, String sourceHint) {
    }

    /**
     * One line of the paste. {@code id} is a client-local index (stable only within this response).
     * {@code scope} is the suggested scope. {@code duplicateOf} is the text of the memory the caller already
     * has that this line repeats; {@code rejectedReason} is a stable code (see {@link MemoryImportParser}).
     */
    public record PreviewFact(int id, String text, String scope, String duplicateOf, String rejectedReason) {
    }

    /**
     * {@code lines}: non-blank lines read. {@code kept}: usable, new facts. {@code duplicates}: repeats of an
     * earlier line or of an existing memory. {@code rejected}: lines refused (see reason codes), whether or not listed.
     */
    public record PreviewStats(int lines, int kept, int duplicates, int rejected) {
    }

    /**
     * Room the caller has left, so the UI can warn before importing more than will fit:
     * {@code sharedRemaining} shared facts and {@code perAgentLimit} private facts per agent in total.
     */
    public record Capacity(int sharedRemaining, int perAgentLimit) {
    }

    public record PreviewResponse(List<PreviewFact> facts, PreviewStats stats, Capacity capacity, String source) {
    }

    /**
     * What {@code GET /api/memory/import/limits} answers when the feature is on (404 when off): lets the page
     * hide the import card up front and show the paste-size limit.
     */
    public record ImportLimits(int maxFacts, int maxChars) {
    }

    /** {@code scope} "shared" or "agent"; "agent" needs {@code agentId} (an agent the caller may run). */
    public record ImportFact(String text, String scope, UUID agentId) {
    }

    public record ImportRequest(List<ImportFact> facts, String source) {
    }

    /** {@code reason} is a stable code the UI maps to words. {@code text} is "[hidden]" for sensitive lines. */
    public record SkippedFact(String text, String reason) {
    }

    public record ImportResponse(int saved, List<SkippedFact> skipped, String source) {
    }
}
