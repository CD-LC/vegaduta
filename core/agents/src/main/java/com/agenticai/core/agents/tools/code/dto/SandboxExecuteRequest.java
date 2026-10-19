package com.agenticai.core.agents.tools.code.dto;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.Size;
import java.util.Map;

/**
 * {@code POST /v1/execute} request. {@code input} is arbitrary JSON (whatever the model sent as
 * this turn's tool input, or the sample the editor's "Test" button supplied) and is passed to the
 * program untouched - core does not inspect, reshape, or sanitize it, since only the tenant's own
 * program knows what shape it expects.
 *
 * <p>{@code timeoutMs}/{@code memoryMb} are already clamped into the operator's configured ceilings
 * by {@code CustomCodeToolService} before they get here.
 */
public record SandboxExecuteRequest(
        String language,
        String toolName,
        String source,
        JsonNode input,
        int timeoutMs,
        int memoryMb,
        /**
         * Resolved vault secrets for this one execution, keyed by the name the program refers to
         * them by. Always a fresh map per call and never cached, logged, or persisted - it exists
         * only for the duration of the HTTP call to the sandbox.
         *
         * <p>The sandbox passes these to the <i>trusted harness</i> as environment variables, not
         * to the tenant program directly: tenant code cannot read the environment (Python's
         * allow-list has no {@code os}; Java's verifier denies {@code java.lang.System}, "the door
         * to getenv"), so the harness is the only thing that can see them, and it hands them over
         * through a narrow API. Never inlined into {@link #source} or {@link #input}, both of which
         * the model can see.
         */
        @Size(max = 50) Map<String, @Size(max = 4096) String> secrets) {

    /** Back-compat constructor for the no-secrets case, which is most tools. */
    public SandboxExecuteRequest(
            String language,
            String toolName,
            String source,
            JsonNode input,
            int timeoutMs,
            int memoryMb) {
        this(language, toolName, source, input, timeoutMs, memoryMb, Map.of());
    }

    public SandboxExecuteRequest {
        secrets = secrets == null ? Map.of() : Map.copyOf(secrets);
    }
}
