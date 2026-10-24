package com.agenticai.core.agents.turn;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * Applies a run-scoped tool selection ({@link ChatTurnRequest#toolNames()}) to the tools a turn resolved.
 *
 * <p><b>Narrowing only.</b> The input is the list the agent's tool resolution already produced for this caller and
 * this turn: allow-list filtered, relevance selected, each tool already wrapped in its approval, autonomy,
 * guardrail and logging chain. This class removes entries from that list and nothing else: it never adds a tool, never
 * replaces a wrapper and never touches a gate, so a tool that survives is exactly as gated as it would have been
 * without a selection. A name that is not in the list is not "a tool to add": it is refused, with a message that
 * names it.
 *
 * <p>The tool-search pair ({@code search_tools} / {@code call_tool}) is NOT kept alongside, unlike the RL tool-subset
 * arm: with on-demand tool loading they are the way to reach every deferred tool, so keeping them would let the model
 * reach tools the selection left out.
 */
final class RunToolSelection {

    private static final Logger log = LoggerFactory.getLogger(RunToolSelection.class);

    /** Names listed in the refusal; the rest are summarised, so the message stays short for a large catalog. */
    private static final int MAX_LISTED = 40;

    private RunToolSelection() {}

    /**
     * Keeps in {@code tools} only the tools named in {@code names} (in place: the dispatch lambda captures the
     * list). {@code names} empty leaves the run without tools.
     *
     * @param strict a name the list does not hold refuses the request (400). {@code false} drops such a name
     *     instead, for the turn the platform starts by itself after an approval: the person chose the selection
     *     long ago and a tool may since have been removed from the agent, which must not stop the task going on
     *     (it still can never gain a tool the selection did not name)
     */
    static void narrow(List<ToolCallback> tools, List<String> names, boolean strict) {
        Set<String> offered = new LinkedHashSet<>();
        for (ToolCallback tool : tools) {
            offered.add(tool.getToolDefinition().name());
        }
        Set<String> wanted = new LinkedHashSet<>(names);
        Set<String> unknown = new LinkedHashSet<>(wanted);
        unknown.removeAll(offered);
        if (!unknown.isEmpty()) {
            if (strict) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, refusal(unknown, offered));
            }
            log.info(
                    "Run tool selection: {} no longer offered to the agent, dropped from the continuation",
                    unknown);
            wanted.removeAll(unknown);
        }
        tools.removeIf(tool -> !wanted.contains(tool.getToolDefinition().name()));
    }

    private static String refusal(Set<String> unknown, Set<String> offered) {
        return "toolNames names "
                + (unknown.size() == 1 ? "a tool" : unknown.size() + " tools")
                + " this agent does not offer to you for this run: "
                + listed(unknown)
                + ". A run can only be narrowed to the agent's own tools, never given another one."
                + (offered.isEmpty()
                        ? " The agent offers no tools."
                        : " The agent offers: " + listed(new TreeSet<>(offered)) + ".");
    }

    private static String listed(Set<String> names) {
        List<String> shown = names.stream().limit(MAX_LISTED).toList();
        String text = String.join(", ", shown);
        return names.size() > shown.size() ? text + ", and " + (names.size() - shown.size()) + " more" : text;
    }
}
