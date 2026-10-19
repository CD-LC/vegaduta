package com.agenticai.core.templates.dto;

import java.util.List;

/**
 * Result of checking a template against the marketplace quality bar.
 *
 * <p>{@code blockers} stop a submission from entering the review queue; {@code advisories} are
 * shown to the owner and the reviewer but never block. {@code derivedTags} are the machine tags
 * (tool:/mcp:/setup:) the platform adds on submit so gallery filtering and the "Needs setup" panel
 * agree.
 */
public record TemplateReadinessReport(
        boolean ready,
        List<Issue> blockers,
        List<Issue> advisories,
        List<TemplateRequirement> requirements,
        List<String> derivedTags) {

    /**
     * @param code stable id, safe for tests and UI keys (e.g. {@code PROMPT_TRUNCATED})
     * @param principle the agentic-AI principle this protects (e.g. "Human oversight")
     * @param message what is wrong, in plain words
     * @param fix what the owner should do about it
     */
    public record Issue(String code, String principle, String message, String fix) {}
}
