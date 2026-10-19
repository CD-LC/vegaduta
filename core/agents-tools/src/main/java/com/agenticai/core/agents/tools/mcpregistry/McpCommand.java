package com.agenticai.core.agents.tools.mcpregistry;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.UUID;

/**
 * A control-plane request routed between replicas. Control ops (CONNECT, DISCONNECT, TEST) carry no
 * secrets: only the tenant schema key, the registration id and (for TEST/CALL) a reply key. {@code
 * CALL} additionally carries {@code payload} = JSON {@code {"tool":..,"args":..}} (tool arguments,
 * so it only exists when {@code app.mcp.registry-route-tool-calls=true}).
 *
 * <p>Null fields are omitted from the JSON: a replica running the previous build has no {@code
 * payload} property and would reject (and silently drop) a command that carried {@code
 * "payload":null} during a rolling deploy.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record McpCommand(
        Op op, String tenant, UUID registrationId, String replyKey, String payload) {

    public enum Op {
        CONNECT,
        DISCONNECT,
        TEST,
        CALL
    }

    public McpCommand(Op op, String tenant, UUID registrationId, String replyKey) {
        this(op, tenant, registrationId, replyKey, null);
    }
}
