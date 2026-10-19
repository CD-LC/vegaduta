package com.agenticai.core.agents.research.dto;

import java.util.List;

/**
 * Body of {@code POST /api/research/runs}.
 *
 * <p>No identity fields: tenant and user come from the authenticated context. No agent id either - the
 * run always uses the caller's own research agent. Validation (lengths, depth values, URL shape) lives in
 * {@code ResearchRunService} so the HTTP path and any future caller get the same rules and the same
 * 400 messages.
 *
 * @param question what to research; required, at most 4000 characters
 * @param depth    {@code auto} (default when null/blank), {@code simple}, {@code comparison} or {@code complex}
 * @param seedUrls optional, at most 3 public http(s) URLs to read in addition to the search results
 * @param language optional language to write the report in (free text, at most 40 characters)
 */
public record ResearchRunStartRequest(
    String question,
    String depth,
    List<String> seedUrls,
    String language
) {
}
