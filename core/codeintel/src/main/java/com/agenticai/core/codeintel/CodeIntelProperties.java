package com.agenticai.core.codeintel;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code code-intel.*} - how core reaches the standalone code-intel
 * microservice. All optional; defaults target a local service on :8090.
 *
 * <p>{@link #enabled} is the master switch: when {@code false} (the default),
 * {@link CodeIntelClient} is not created (see its
 * {@code @ConditionalOnProperty}), so no code-intel tools are ever offered
 * to agents and nothing changes from today's behavior. An operator opts in
 * by setting {@code code-intel.enabled=true} and pointing {@link #baseUrl}/
 * {@link #internalToken} at a running service.
 */
@ConfigurationProperties(prefix = "code-intel")
public class CodeIntelProperties {

    /** Master switch. Default false - no tools offered until an operator opts in. */
    private boolean enabled = false;

    /** Root URL of the code-intel service, e.g. {@code http://code-intel:8090} in compose. */
    private String baseUrl = "http://localhost:8090";

    /**
     * Shared secret sent as {@code X-Internal-Token} - must match the
     * service's {@code CODE_INTEL_INTERNAL_TOKEN} (service run with
     * {@code CODE_INTEL_AUTH_MODE=internal}). Never logged.
     */
    private String internalToken = "";

    /** Per-call timeout (ms) for retrieval/graph reads. */
    private int timeoutMillis = 15000;

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

    public int getTimeoutMillis() {
        return timeoutMillis;
    }

    public void setTimeoutMillis(int timeoutMillis) {
        this.timeoutMillis = timeoutMillis;
    }
}
