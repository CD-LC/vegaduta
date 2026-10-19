package com.agenticai.core.agents.tools;

import io.modelcontextprotocol.client.transport.ServerParameters;
import io.modelcontextprotocol.client.transport.StdioClientTransport;
import io.modelcontextprotocol.json.McpJsonMapper;

/**
 * {@link StdioClientTransport} that does NOT leak core's own process environment into the spawned
 * MCP server child (MCP-DOCTOR hardening, 2026-07-25).
 *
 * <p><b>The hole, verified against the bundled SDK 0.18.2 bytecode:</b> the SDK's spawn path
 * ({@code lambda$connect$1}) does {@code getProcessBuilder() -> pb.command(...) ->
 * pb.environment().putAll(params.getEnv()) -> pb.start()}. A fresh {@code ProcessBuilder}'s {@code
 * environment()} STARTS AS A FULL COPY OF THE PARENT PROCESS ENV, and the SDK only ever ADDS to it
 * - so every dynamic STDIO MCP server child inherited core's entire environment: {@code
 * APP_ENCRYPTION_KEY}, datasource credentials, Keycloak secrets, every service token in
 * deploy/.env. {@code ServerParameters}'s own {@code DEFAULT_INHERITED_ENV_VARS} allowlist
 * (PATH/HOME/APPDATA/... - platform-appropriate) only shapes what {@code params.getEnv()} contains;
 * it never removed the inherited copy.
 *
 * <p><b>The fix:</b> {@code getProcessBuilder()} is {@code protected} - exactly the seam needed.
 * This subclass clears the inherited environment before handing the builder back; the SDK's own
 * {@code putAll(params .getEnv())} then re-populates it with precisely the right set: the SDK's
 * minimal safe default vars + the registration's own env entries. Nothing else reaches the child.
 */
final class EnvIsolatedStdioClientTransport extends StdioClientTransport {

    EnvIsolatedStdioClientTransport(ServerParameters params, McpJsonMapper jsonMapper) {
        super(params, jsonMapper);
    }

    @Override
    protected ProcessBuilder getProcessBuilder() {
        ProcessBuilder pb = super.getProcessBuilder();
        // The SDK putAll()s params.getEnv() (safe defaults + registration env)
        // on top of this immediately after - see class javadoc.
        pb.environment().clear();
        return pb;
    }
}
