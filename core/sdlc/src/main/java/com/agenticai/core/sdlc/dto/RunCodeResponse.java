package com.agenticai.core.sdlc.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import java.util.List;

/**
 * Result of {@link com.agenticai.core.sdlc.E2bCodeExecutionClient#runCode} - mirrors the JSON shape
 * core/sdlc/e2b-template/runner.py's {@code POST /run} returns. {@code artifacts} (data-analysis
 * sandbox) are consumed in-process by the agent tool and deliberately kept out of the REST JSON
 * ({@code @JsonIgnore}) so the manual {@code /run} endpoint's contract is unchanged.
 */
public record RunCodeResponse(
    String stdout, String stderr, Integer exitCode, boolean timedOut, String sandboxId,
    @JsonIgnore List<SandboxArtifact> artifacts
) {

    public RunCodeResponse(String stdout, String stderr, Integer exitCode, boolean timedOut, String sandboxId) {
        this(stdout, stderr, exitCode, timedOut, sandboxId, List.of());
    }
}
