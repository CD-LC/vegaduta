package com.agenticai.core.apidocs;

import java.util.Map;

/**
 * The published name of a controller in the OpenAPI spec, where it differs from the class's simple
 * name.
 *
 * <p>The generator derives each operation's tag and its {@code operationId} prefix from the
 * controller class name, and the SDK generator ({@code sdk/generate.mjs}) groups methods into one
 * client class per tag. Splitting a controller for size therefore renames its operations and moves
 * them to a new SDK group, which breaks TypeScript and Python SDK callers even though every URL is
 * unchanged. A controller listed here is published under the name of the class it was split out of,
 * so its tag and operationIds stay what they were before the split.
 *
 * <p>Keyed by fully qualified class name. The handler method names of an aliased controller must not
 * collide with those of the class it is published as; a collision would get a numeric suffix from
 * {@code uniqueOperationId} and change the SDK method name.
 */
final class OpenApiControllerAliases {

    private static final Map<String, String> PUBLISHED_AS =
            Map.of(
                    // Split out of WorkflowController for the file-size cap (2026-10).
                    "com.agenticai.core.workflow.WorkflowRunControlController", "WorkflowController",
                    "com.agenticai.core.workflow.WorkflowNodeTriggerController", "WorkflowController",
                    // The chat SSE endpoint, moved out of AgentController to core/agents-turn
                    // (ADR-0023) so the kernel controller does not depend on the turn path.
                    "com.agenticai.core.agents.turn.AgentChatStreamController", "AgentController");

    private OpenApiControllerAliases() {}

    /** The name the spec publishes {@code controller} under: an alias, else its simple name. */
    static String publishedName(Class<?> controller) {
        return PUBLISHED_AS.getOrDefault(controller.getName(), controller.getSimpleName());
    }

    /** Every aliased class name, for the test that checks each one still exists. */
    static Iterable<String> aliasedClassNames() {
        return PUBLISHED_AS.keySet();
    }
}
