package com.agenticai.core.agents.tools.code.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * {@code POST /v1/execute} response - one record covering both arms of the contract, discriminated
 * by {@link #ok()}.
 *
 * <p>Modelled as a single record rather than a sealed pair because that is what the wire actually
 * is (one object whose fields differ by outcome), and because every caller here has to branch on
 * {@code ok} anyway. Fields belonging to the other arm are simply absent/null.
 *
 * <p>Like validate, a FAILED execution is still HTTP 200: "your program threw" or "your program
 * timed out" is a result, not a service fault.
 *
 * @param errorType one of {@code
 *     VALIDATION|COMPILE|RUNTIME|TIMEOUT|MEMORY|OUTPUT_TOO_LARGE|INTERNAL}; left as a String rather
 *     than an enum so an errorType this build has never heard of can still be surfaced verbatim
 *     instead of failing deserialization.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record SandboxExecuteResponse(
        boolean ok,
        JsonNode result,
        String stdout,
        String stderr,
        String errorType,
        String error,
        int durationMs,
        boolean truncated) {}
