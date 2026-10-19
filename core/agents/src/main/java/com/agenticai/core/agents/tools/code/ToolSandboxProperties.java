package com.agenticai.core.agents.tools.code;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code tool-sandbox.*} - how core reaches the standalone tool-sandbox microservice that compiles
 * and runs tenant-authored {@link CustomCodeTool} programs.
 *
 * <p>{@link #enabled} is the master switch, and it is off by default for the same reason {@code
 * CodeIntelProperties#enabled} is: with {@code false} the {@link ToolSandboxClient} bean is never
 * created, so no custom code tool can be validated or run, and nothing about today's behavior
 * changes. An operator opts in by setting {@code TOOL_SANDBOX_ENABLED=true} and pointing {@link
 * #baseUrl}/{@link #internalToken} at a running sandbox.
 *
 * <p>The size/time/memory caps here are the platform's ceilings, not the tenant's preference:
 * {@code CustomCodeToolService} clamps whatever a tenant asks for into them at save time, so a
 * stored tool can never carry limits the operator did not sanction. The sandbox enforces its own
 * copies of these independently - these are the outer bound core is willing to request, never the
 * thing that makes execution safe.
 */
@ConfigurationProperties(prefix = "tool-sandbox")
public class ToolSandboxProperties {

    /** Master switch. Default false - no custom code tools until an operator opts in. */
    private boolean enabled = false;

    /** Root URL of the tool-sandbox service, e.g. {@code http://tool-sandbox:8095} in compose. */
    private String baseUrl = "http://tool-sandbox:8095";

    /** Shared secret sent as {@code X-Internal-Token} on every call. Never logged. */
    private String internalToken = "";

    /** TCP connect budget (ms). */
    private int connectTimeoutMillis = 5000;

    /**
     * Whole-call budget (ms). Must comfortably exceed {@link #maxTimeoutMs} - the sandbox's own
     * per-program wall clock - since a request also covers compile time and transport.
     */
    private int requestTimeoutMillis = 30000;

    /**
     * L6 - run Python custom tools in the tenant's own E2B sandbox (Firecracker microVM, see {@link
     * E2bCustomToolExecutor}) instead of the tool-sandbox service. Default false. Validation still
     * uses tool-sandbox; Java tools and tools holding vault secrets still execute in tool-sandbox.
     */
    private boolean e2bExecution = false;

    public boolean isE2bExecution() {
        return e2bExecution;
    }

    public void setE2bExecution(boolean e2bExecution) {
        this.e2bExecution = e2bExecution;
    }

    /** Largest tenant-authored program core will accept, in UTF-8 bytes. */
    private int maxSourceBytes = 65536;

    /** Per-call wall clock applied when a tool does not specify one. */
    private int defaultTimeoutMs = 5000;

    /** Hard ceiling a tenant's requested timeout is clamped into. */
    private int maxTimeoutMs = 15000;

    /** Per-call memory ceiling applied when a tool does not specify one. */
    private int defaultMemoryMb = 128;

    /** Hard ceiling a tenant's requested memory is clamped into. */
    private int maxMemoryMb = 512;

    /** Standing cap on saved custom code tools per tenant schema. */
    private int maxToolsPerTenant = 25;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public String getInternalToken() {
        return internalToken;
    }

    public void setInternalToken(String internalToken) {
        this.internalToken = internalToken;
    }

    public int getConnectTimeoutMillis() {
        return connectTimeoutMillis;
    }

    public void setConnectTimeoutMillis(int connectTimeoutMillis) {
        this.connectTimeoutMillis = connectTimeoutMillis;
    }

    public int getRequestTimeoutMillis() {
        return requestTimeoutMillis;
    }

    public void setRequestTimeoutMillis(int requestTimeoutMillis) {
        this.requestTimeoutMillis = requestTimeoutMillis;
    }

    public int getMaxSourceBytes() {
        return maxSourceBytes;
    }

    public void setMaxSourceBytes(int maxSourceBytes) {
        this.maxSourceBytes = maxSourceBytes;
    }

    public int getDefaultTimeoutMs() {
        return defaultTimeoutMs;
    }

    public void setDefaultTimeoutMs(int defaultTimeoutMs) {
        this.defaultTimeoutMs = defaultTimeoutMs;
    }

    public int getMaxTimeoutMs() {
        return maxTimeoutMs;
    }

    public void setMaxTimeoutMs(int maxTimeoutMs) {
        this.maxTimeoutMs = maxTimeoutMs;
    }

    public int getDefaultMemoryMb() {
        return defaultMemoryMb;
    }

    public void setDefaultMemoryMb(int defaultMemoryMb) {
        this.defaultMemoryMb = defaultMemoryMb;
    }

    public int getMaxMemoryMb() {
        return maxMemoryMb;
    }

    public void setMaxMemoryMb(int maxMemoryMb) {
        this.maxMemoryMb = maxMemoryMb;
    }

    public int getMaxToolsPerTenant() {
        return maxToolsPerTenant;
    }

    public void setMaxToolsPerTenant(int maxToolsPerTenant) {
        this.maxToolsPerTenant = maxToolsPerTenant;
    }
}
