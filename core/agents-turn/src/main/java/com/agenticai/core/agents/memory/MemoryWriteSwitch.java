package com.agenticai.core.agents.memory;

/**
 * The shared wording of a memory write that {@code memory.write.enabled} switched off, so the tools
 * that answer with it and the turn journal that counts it agree on one marker. Nothing here carries
 * the fact's text.
 */
public final class MemoryWriteSwitch {

    private MemoryWriteSwitch() {}

    /** The {@code reason} a skipped write names; the journal looks for it in a tool result. */
    public static final String REASON = "memory_write_disabled";

    /**
     * The result a memory tool returns, without having written anything, while writes are switched
     * off.
     */
    public static String skippedResult() {
        return "{\"remembered\": false, \"skipped\": true, \"reason\": \""
                + REASON
                + "\", \"note\": "
                + "\"Memory is disabled for this agent, so nothing was saved. Carry on without remembering it.\"}";
    }

    /** True when a tool result is {@link #skippedResult()}. */
    public static boolean isSkippedResult(String result) {
        return result != null && result.contains("\"reason\": \"" + REASON + "\"");
    }
}
