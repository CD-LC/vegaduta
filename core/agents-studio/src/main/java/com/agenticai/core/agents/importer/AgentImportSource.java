package com.agenticai.core.agents.importer;

import java.util.Locale;
import java.util.Optional;

/**
 * The platforms agents can be imported from. {@link #wire()} is the string the
 * REST contract uses for {@code source} and {@code detectedSource}.
 *
 * <p>Pure: no Spring, no I/O.
 */
public enum AgentImportSource {

    /** OpenAI Assistants API {@code assistant} object. */
    OPENAI_ASSISTANT("openai-assistant"),
    /** OpenAI Agents SDK / Agent Builder style agent config (JSON mirroring an {@code Agent(...)}). */
    OPENAI_AGENTS("openai-agents"),
    /** Flowise chatflow / agentflow export JSON. */
    FLOWISE("flowise");

    /** The request value that asks the server to detect the shape itself. */
    public static final String AUTO = "auto";

    private final String wire;

    AgentImportSource(String wire) {
        this.wire = wire;
    }

    public String wire() {
        return wire;
    }

    /**
     * Parses a request {@code source} value. Empty for {@code auto}, blank/null and anything
     * unrecognised - the caller distinguishes those with {@link #isAuto(String)}.
     */
    public static Optional<AgentImportSource> parse(String raw) {
        if (raw == null) {
            return Optional.empty();
        }
        String key = raw.trim().toLowerCase(Locale.ROOT).replace('_', '-');
        return switch (key) {
            case "openai-assistant", "openai-assistants", "assistant", "assistants", "assistants-api" ->
                Optional.of(OPENAI_ASSISTANT);
            case "openai-agents", "openai-agent", "agents-sdk", "openai-agents-sdk", "agent-builder",
                 "openai-agent-builder" -> Optional.of(OPENAI_AGENTS);
            case "flowise" -> Optional.of(FLOWISE);
            default -> Optional.empty();
        };
    }

    /** True for a missing/blank source or the literal {@code auto}. */
    public static boolean isAuto(String raw) {
        return raw == null || raw.isBlank() || AUTO.equalsIgnoreCase(raw.trim());
    }
}
