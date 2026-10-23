package com.agenticai.core.agents.runtime;

import java.util.Locale;

/**
 * The agent-runtime settings that can be overridden per tenant and per agent. One naming convention
 * everywhere: {@link #apiName()} is the camelCase JSON field (agent/tenant APIs, the {@code layers} map
 * of {@link AgentRuntimeSettings}); {@link #configName()} is the kebab-case segment under {@code
 * app.agent-runtime.bounds.*} that bounds it.
 */
public enum RuntimeSetting {
    /** Tool calls one turn may make (the ceiling {@code GuardedToolCallback} enforces). */
    MAX_TOOL_STEPS("maxToolSteps"),
    /** Wall-clock seconds one turn may run. */
    TURN_TIMEOUT_SECONDS("turnTimeoutSeconds"),
    /** Wall-clock seconds one tool call may run; a platform default of 0 means no limit. */
    TOOL_TIMEOUT_SECONDS("toolTimeoutSeconds"),
    /** Characters of one tool result that reach the model. */
    TOOL_OUTPUT_MAX_CHARS("toolOutputMaxChars"),
    /** Knowledge-base chunks one search returns. */
    RETRIEVAL_TOP_K("retrievalTopK"),
    /** Least relevance score a knowledge-base chunk needs to be returned; 0 returns every hit. */
    RETRIEVAL_MIN_SCORE("retrievalMinScore"),
    /** Extra model passes after a reply that stopped at a step it announced and did not take. */
    CONTINUATION_PASSES("continuationPasses");

    private final String apiName;

    RuntimeSetting(String apiName) {
        this.apiName = apiName;
    }

    /** The camelCase JSON name. */
    public String apiName() {
        return apiName;
    }

    /** The kebab-case name used under {@code app.agent-runtime.bounds}. */
    public String configName() {
        return name().toLowerCase(Locale.ROOT).replace('_', '-');
    }
}
