package com.agenticai.core.agents.turn;

import java.util.List;
import java.util.function.Predicate;
import org.springframework.ai.tool.ToolCallback;

/**
 * Per-call shaping of an agent-to-agent delegated turn: text appended to the delegate's system
 * prompt (a worker directive) and a filter over the tool names it may use. Both are optional; a
 * {@code null} {@code DelegateOptions} is the unchanged delegated call.
 *
 * @param systemAddendum appended to the system prompt after forwarded context, or null
 * @param toolFilter keeps a tool when it returns true, or null to keep every resolved tool
 */
public record DelegateOptions(String systemAddendum, Predicate<String> toolFilter) {

    /** {@code tools} narrowed by {@code options}; the same list when there is nothing to apply. */
    static List<ToolCallback> filter(DelegateOptions options, List<ToolCallback> tools) {
        if (options == null || options.toolFilter() == null) {
            return tools;
        }
        return tools.stream()
                .filter(t -> options.toolFilter().test(t.getToolDefinition().name()))
                .toList();
    }

    /** {@code prompt} with the addendum appended as its own block, or unchanged when none. */
    static String append(DelegateOptions options, String prompt) {
        String extra = options == null ? null : options.systemAddendum();
        return extra == null || extra.isBlank() ? prompt : prompt + "\n\n" + extra;
    }
}
