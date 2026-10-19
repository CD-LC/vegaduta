package com.agenticai.core.agents.tools;

import java.util.ArrayList;
import java.util.Collection;
import org.springframework.ai.tool.ToolCallback;

/**
 * A tool list that remembers one fact learned while it was resolved: whether any MCP-sourced
 * candidate tool was found. {@code AgentToolProvider#resolveToolsFor} returns one of these (typed
 * as a plain {@code List}), so a caller that needs the answer can read it instead of resolving the
 * MCP tools a second time through {@code AgentToolProvider#anyMcpToolsOfferedFor}.
 *
 * <p>The value is the same one the second resolution computes: whether the MCP candidate set, taken
 * before relevance trimming, is non-empty. A copy made by a plain {@code new ArrayList<>(list)}
 * loses it; {@link #copyOf} keeps it.
 */
public final class ResolvedToolList extends ArrayList<ToolCallback> {

    private static final long serialVersionUID = 1L;

    private final transient Boolean anyMcpToolsOffered;

    ResolvedToolList(Collection<? extends ToolCallback> tools, boolean anyMcpToolsOffered) {
        super(tools);
        this.anyMcpToolsOffered = anyMcpToolsOffered;
    }

    private ResolvedToolList(Collection<? extends ToolCallback> tools, Boolean anyMcpToolsOffered) {
        super(tools);
        this.anyMcpToolsOffered = anyMcpToolsOffered;
    }

    /** A list that carries the given MCP answer, for a resolver (or a test double of one). */
    public static ResolvedToolList of(
            Collection<? extends ToolCallback> tools, boolean anyMcpToolsOffered) {
        return new ResolvedToolList(tools, anyMcpToolsOffered);
    }

    /** An empty list that carries no MCP answer. */
    public static ResolvedToolList empty() {
        return new ResolvedToolList(java.util.List.of(), (Boolean) null);
    }

    /** A mutable copy that keeps the MCP answer when {@code tools} carries one. */
    public static ResolvedToolList copyOf(Collection<? extends ToolCallback> tools) {
        return new ResolvedToolList(
                tools,
                tools instanceof ResolvedToolList resolved ? resolved.anyMcpToolsOffered : null);
    }

    /** True or false when the resolution recorded the answer; {@code null} when it did not. */
    public Boolean anyMcpToolsOffered() {
        return anyMcpToolsOffered;
    }
}
