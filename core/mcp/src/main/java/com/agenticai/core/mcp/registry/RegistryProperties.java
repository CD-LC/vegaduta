package com.agenticai.core.mcp.registry;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code mcp.registry.*} - how core reaches the official public MCP Registry
 * (registry.modelcontextprotocol.io) for the "Browse registry" search. Same
 * plain-{@code @ConfigurationProperties} style as {@code CodeIntelProperties}
 * (picked up by the app's configuration-properties scan; no {@code @Component}
 * needed).
 *
 * <p>{@link #enabled} defaults true because this is a read-only, unauthenticated
 * public API and the search degrades gracefully if it's unreachable (see
 * {@link OfficialMcpRegistryClient} - a failed call returns an empty page, it
 * never breaks the Tools screen). An operator can set {@code
 * mcp.registry.enabled=false} for an air-gapped deployment to hide the browse
 * feature entirely.
 */
@ConfigurationProperties(prefix = "mcp.registry")
public class RegistryProperties {

    /** Master switch for the "Browse registry" search. Default true. */
    private boolean enabled = true;

    /** Base URL of the official registry. Override only for a private mirror. */
    private String baseUrl = "https://registry.modelcontextprotocol.io";

    /** TCP connect timeout (ms). Kept short - a reachable registry connects fast. */
    private int timeoutMillis = 8000;

    /**
     * Read timeout (ms) for one registry page. Deliberately generous: the
     * public registry regularly takes 15-45s to answer a single {@code
     * /v0/servers} call (measured 2026-07-24 - TTFB dominates, DNS/TLS are
     * &lt;1s, and page size doesn't change it). Nothing user-facing waits on
     * this - it's the background catalog refresh that pays the cost - so
     * being patient here is strictly better than timing out and showing an
     * empty browse tab.
     */
    private int readTimeoutMillis = 60000;

    /** How many results one page of the browse UI shows. */
    private int pageSize = 30;

    /**
     * How long (seconds) an identical search's result page is cached, so
     * paging back and forth or repeat searches don't re-hit the registry on
     * every keystroke-debounced call. 0 disables caching. Only used by the
     * cold-catalog fallback path - a warm catalog is served from memory and
     * never touches the network.
     */
    private int cacheTtlSeconds = 120;

    /**
     * How long (seconds) a fetched catalog snapshot is considered fresh. A
     * stale snapshot is still served immediately while a refresh runs in the
     * background - the registry's contents change on the order of hours, and
     * serving 6-hour-old data instantly beats making someone wait 20s for
     * today's. Default 6h.
     */
    private int catalogTtlSeconds = 21600;

    /** Page size used when walking the whole catalog in the background. */
    private int catalogPageSize = 100;

    /** Safety cap on pages walked per refresh, so a broken cursor can't loop forever. */
    private int catalogMaxPages = 300;

    /** Safety cap on servers held in memory. */
    private int catalogMaxServers = 30000;

    /**
     * Whether to start the first catalog fetch at application startup, so the
     * first person to open the browse tab gets an instant, already-warm
     * search instead of paying the cold-fetch cost themselves. Runs on a
     * daemon thread and never blocks startup.
     */
    private boolean warmOnStartup = true;

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

    public int getTimeoutMillis() {
        return timeoutMillis;
    }

    public void setTimeoutMillis(int timeoutMillis) {
        this.timeoutMillis = timeoutMillis;
    }

    public int getReadTimeoutMillis() {
        return readTimeoutMillis;
    }

    public void setReadTimeoutMillis(int readTimeoutMillis) {
        this.readTimeoutMillis = readTimeoutMillis;
    }

    public int getPageSize() {
        return pageSize;
    }

    public void setPageSize(int pageSize) {
        this.pageSize = pageSize;
    }

    public int getCatalogTtlSeconds() {
        return catalogTtlSeconds;
    }

    public void setCatalogTtlSeconds(int catalogTtlSeconds) {
        this.catalogTtlSeconds = catalogTtlSeconds;
    }

    public int getCatalogPageSize() {
        return catalogPageSize;
    }

    public void setCatalogPageSize(int catalogPageSize) {
        this.catalogPageSize = catalogPageSize;
    }

    public int getCatalogMaxPages() {
        return catalogMaxPages;
    }

    public void setCatalogMaxPages(int catalogMaxPages) {
        this.catalogMaxPages = catalogMaxPages;
    }

    public int getCatalogMaxServers() {
        return catalogMaxServers;
    }

    public void setCatalogMaxServers(int catalogMaxServers) {
        this.catalogMaxServers = catalogMaxServers;
    }

    public boolean isWarmOnStartup() {
        return warmOnStartup;
    }

    public void setWarmOnStartup(boolean warmOnStartup) {
        this.warmOnStartup = warmOnStartup;
    }

    public int getCacheTtlSeconds() {
        return cacheTtlSeconds;
    }

    public void setCacheTtlSeconds(int cacheTtlSeconds) {
        this.cacheTtlSeconds = cacheTtlSeconds;
    }
}
