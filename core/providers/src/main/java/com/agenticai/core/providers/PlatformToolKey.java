package com.agenticai.core.providers;

/** The fixed, known {@code tool_key} values {@link PlatformToolCredential} rows use - see V174's migration comment. */
public final class PlatformToolKey {
    public static final String ELEVENLABS = "ELEVENLABS";
    public static final String E2B = "E2B";

    /**
     * Brave Search API key behind the {@code web_search} built-in. Platform-
     * supplied, not tenant BYOK: search is a subsidised out-of-the-box
     * capability, so a brand-new tenant can research without configuring
     * anything. No Flyway migration is needed - {@code tool_key} is the
     * free-text primary key of platform_tool_credential, so a new constant
     * needs no schema change.
     */
    public static final String BRAVE_SEARCH = "BRAVE_SEARCH";

    /**
     * Alternative {@code web_search} providers (app.web-search.provider / fallback - see
     * {@code WebSearchProviderRegistry}). Each is just an API key in the same Tool Credentials store as
     * BRAVE_SEARCH; none is used unless the operator selects it in configuration. No Flyway migration:
     * {@code tool_key} is free text.
     */
    public static final String TAVILY_SEARCH = "TAVILY_SEARCH";
    public static final String EXA_SEARCH = "EXA_SEARCH";
    public static final String SERPER_SEARCH = "SERPER_SEARCH";

    /**
     * Managed-browser provider credential behind the {@code web_task} built-in.
     * {@code apiKey} is the provider key (Steel, Browserbase, or a self-hosted
     * browser-task gateway); {@code extraConfig} carries the provider base URL
     * (e.g. {@code https://api.steel.dev} or an in-cluster
     * {@code http://steel:3000}). See {@code HttpWebTaskBackend}'s javadoc for
     * the provider contract.
     */
    public static final String BROWSER_TASK = "BROWSER_TASK";

    /**
     * TypeSafe AI key for Jev, the System One decision model behind the
     * platform's cheap-decision paths (cost-routing complexity check, Quick
     * Task agent selection - see {@code JevDecisionService}). Platform-
     * supplied like BRAVE_SEARCH: decisions are a subsidised out-of-the-box
     * capability, not tenant BYOK. No Flyway migration - {@code tool_key} is
     * free-text, same as every key above.
     */
    public static final String TYPESAFE = "TYPESAFE";

    private PlatformToolKey() {
    }
}
