package com.agenticai.core.agents.tools;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.tool.ToolCallback;

/**
 * One origin of tool candidates for an agent turn (knowledge, MCP, A2A and web, browser, sandbox,
 * data tables, scheduled tasks, coding tasks, skills, ...). {@link ToolResolver} collects every
 * {@code ToolSource} bean, in {@code @Order}, and does the merging, allow-list filtering, collision
 * handling and per-turn capping; a source only answers "which guard-wrapped tools do you offer for
 * this turn".
 *
 * <p>A source must not throw for an absent optional collaborator: it contributes an empty list,
 * exactly as the resolver helpers it wraps always did.
 */
interface ToolSource {

    /** Where in the resolution pipeline a source's tools join. */
    enum Kind {
        /** Platform built-in tools; they win a name collision against {@link #MCP}. */
        BUILT_IN,
        /** Tools from MCP servers; dropped when a built-in has the same name. */
        MCP,
        /**
         * Joins AFTER the agent's allow-list filter (context infrastructure such as skills and
         * {@code restore_tool_result}); sees the filtered list as {@code resolvedSoFar}.
         */
        POST_ALLOW_LIST
    }

    /** The six-argument shape every first-party resolver helper already has. */
    @FunctionalInterface
    interface Resolve {
        List<ToolCallback> resolve(
                UUID agentId,
                String sessionId,
                AtomicInteger stepCounter,
                String userMessage,
                ChatClient chatClient,
                boolean rigorous);
    }

    /** Stable identifier, used in logs and tests. */
    String id();

    Kind kind();

    /** Whether this source takes part in this resolution at all. */
    default boolean applies(ToolResolutionContext ctx) {
        return true;
    }

    List<ToolCallback> contribute(ToolResolutionContext ctx);

    static ToolSource ofResolver(String id, Kind kind, Resolve resolve) {
        return of(
                id,
                kind,
                ctx ->
                        resolve.resolve(
                                ctx.agentId(),
                                ctx.sessionId(),
                                ctx.stepCounter(),
                                ctx.userMessage(),
                                ctx.chatClient(),
                                ctx.rigorous()));
    }

    static ToolSource of(
            String id,
            Kind kind,
            java.util.function.Function<ToolResolutionContext, List<ToolCallback>> fn) {
        return new FunctionToolSource(id, kind, fn, ctx -> true);
    }

    static ToolSource of(
            String id,
            Kind kind,
            java.util.function.Predicate<ToolResolutionContext> applies,
            java.util.function.Function<ToolResolutionContext, List<ToolCallback>> fn) {
        return new FunctionToolSource(id, kind, fn, applies);
    }

    /** Function-backed source; the bean configuration builds all of its sources with it. */
    record FunctionToolSource(
            String id,
            Kind kind,
            java.util.function.Function<ToolResolutionContext, List<ToolCallback>> fn,
            java.util.function.Predicate<ToolResolutionContext> appliesWhen)
            implements ToolSource {

        @Override
        public boolean applies(ToolResolutionContext ctx) {
            return appliesWhen.test(ctx);
        }

        @Override
        public List<ToolCallback> contribute(ToolResolutionContext ctx) {
            return fn.apply(ctx);
        }
    }
}
