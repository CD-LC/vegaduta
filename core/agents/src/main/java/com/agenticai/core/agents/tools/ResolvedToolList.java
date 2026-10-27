package com.agenticai.core.agents.tools;

import java.util.ArrayList;
import java.util.Collection;
import org.springframework.ai.tool.ToolCallback;

/**
 * A tool list that remembers facts learned while it was resolved: whether any MCP-sourced candidate
 * tool was found, and whether the agent scratchpad was offered to this agent (post allow-list,
 * before on-demand deferral moved it behind {@code search_tools}). {@code
 * AgentToolProvider#resolveToolsFor} returns one of these (typed as a plain {@code List}), so a
 * caller that needs an answer can read it instead of resolving the tools a second time through
 * {@code AgentToolProvider#anyMcpToolsOfferedFor}.
 *
 * <p>Either fact may be {@code null} when the resolution that produced the list did not record it
 * (a hand-built list, an older copy path). A copy made by a plain {@code new ArrayList<>(list)}
 * loses both; {@link #copyOf} keeps them.
 */
public final class ResolvedToolList extends ArrayList<ToolCallback> {

    private static final long serialVersionUID = 1L;

    private final transient Boolean anyMcpToolsOffered;
    private final transient Boolean scratchpadOffered;

    ResolvedToolList(
            Collection<? extends ToolCallback> tools,
            Boolean anyMcpToolsOffered,
            Boolean scratchpadOffered) {
        super(tools);
        this.anyMcpToolsOffered = anyMcpToolsOffered;
        this.scratchpadOffered = scratchpadOffered;
    }

    /**
     * A list that carries the given MCP answer, for a resolver (or a test double of one) with
     * nothing to say about the scratchpad.
     */
    public static ResolvedToolList of(
            Collection<? extends ToolCallback> tools, boolean anyMcpToolsOffered) {
        return new ResolvedToolList(tools, anyMcpToolsOffered, null);
    }

    /** A list that carries both answers, for a resolver that knows them. */
    public static ResolvedToolList of(
            Collection<? extends ToolCallback> tools,
            Boolean anyMcpToolsOffered,
            Boolean scratchpadOffered) {
        return new ResolvedToolList(tools, anyMcpToolsOffered, scratchpadOffered);
    }

    /** An empty list that carries no answers. */
    public static ResolvedToolList empty() {
        return new ResolvedToolList(java.util.List.of(), null, null);
    }

    /** A mutable copy that keeps the recorded answers when {@code tools} carries them. */
    public static ResolvedToolList copyOf(Collection<? extends ToolCallback> tools) {
        return tools instanceof ResolvedToolList resolved
                ? new ResolvedToolList(
                        tools, resolved.anyMcpToolsOffered, resolved.scratchpadOffered)
                : new ResolvedToolList(tools, null, null);
    }

    /** True or false when the resolution recorded the answer; {@code null} when it did not. */
    public Boolean anyMcpToolsOffered() {
        return anyMcpToolsOffered;
    }

    /**
     * Whether the scratchpad tools were in the resolved candidate set (post allow-list, before
     * deferral) - the reachability signal for the scratchpad prompt directive: on a deferral turn
     * they sit behind {@code search_tools} rather than on this list. {@code null} when the
     * resolution did not record it.
     */
    public Boolean scratchpadOffered() {
        return scratchpadOffered;
    }
}
