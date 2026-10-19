package com.agenticai.core.agents.tools;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code mcp-stdio-sidecar.*} - whether {@code core} spawns dynamic STDIO MCP servers (`npx
 * &lt;pkg&gt;`, `uvx &lt;pkg&gt;`, ...) directly, as it always has, or routes them through the
 * {@code mcp-stdio-runner} sidecar so the real interpreter/package never runs inside this
 * container. See {@code docs/architecture/MCP-STDIO-SANDBOXING-DESIGN.md} §8 for the full design.
 *
 * <p>{@link #enabled} is the master switch, same "off by default, an operator opts in per
 * environment" posture as {@link
 * com.agenticai.core.agents.tools.code.ToolSandboxProperties#isEnabled()}. With {@code false},
 * {@link DynamicMcpConnectionManager#stdioParamsFor} is byte-for-byte what it was before this class
 * existed - nothing about today's behavior changes.
 *
 * <p>Unlike {@code ToolSandboxProperties}, {@code core} itself never opens a connection to the
 * sidecar - only the spawned {@code mcp-stdio-proxy} relay process does, using {@link
 * #address}/{@link #sharedSecret} handed to it via its own process env (see {@link
 * DynamicMcpConnectionManager#wrapForSidecar}). There is deliberately no request-timeout field here
 * for that reason: this class only ever supplies values baked into a spawned process's env, never
 * used to make a call from the JVM itself.
 */
@ConfigurationProperties(prefix = "mcp-stdio-sidecar")
public class McpStdioSidecarProperties {

    /**
     * Master switch. Default false - dynamic STDIO servers spawn directly until an operator opts
     * in.
     */
    private boolean enabled = false;

    /**
     * {@code host:port} of the {@code mcp-stdio-runner} service, e.g. {@code mcp-stdio-runner:8097}
     * in compose. Despite the {@code *_URL}-style naming convention this repo uses elsewhere (see
     * {@code ToolSandboxProperties#getBaseUrl()}), this is a raw TCP address, not an HTTP URL -
     * {@code mcp-stdio-proxy} tolerates an accidental scheme prefix if one is supplied, but does
     * not require one.
     */
    private String address = "mcp-stdio-runner:8097";

    /** Shared secret the relay presents to the sidecar on every spawn request. Never logged. */
    private String sharedSecret = "";

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getAddress() {
        return address;
    }

    public void setAddress(String address) {
        this.address = address;
    }

    public String getSharedSecret() {
        return sharedSecret;
    }

    public void setSharedSecret(String sharedSecret) {
        this.sharedSecret = sharedSecret;
    }
}
