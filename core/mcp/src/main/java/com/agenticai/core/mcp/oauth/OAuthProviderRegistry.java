package com.agenticai.core.mcp.oauth;

import com.agenticai.core.mcp.oauth.OAuthProviderSpec.ClientAuth;
import com.agenticai.core.mcp.oauth.OAuthProviderSpec.RevokeStyle;
import com.agenticai.core.mcp.oauth.OAuthProviderSpec.RevokeToken;
import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * The code-side table of popular SaaS OAuth providers (one-click "Connect",
 * no pasted tokens) overlaid with this deployment's configuration:
 *
 * <pre>
 * app.mcp.oauth.providers-enabled                 master switch, default false
 * app.mcp.oauth.provider-callback-base-url        public API origin, e.g. https://api.example.com
 * app.mcp.oauth.provider-console-redirect-uri     public console page the browser lands on afterwards, e.g. https://app.example.com/tools (no default)
 * app.mcp.oauth.providers.&lt;id&gt;.client-id         platform OAuth app of that provider
 * app.mcp.oauth.providers.&lt;id&gt;.client-secret
 * app.mcp.oauth.providers.&lt;id&gt;.enabled          default true (a blank client id still means "not configured")
 * app.mcp.oauth.providers.&lt;id&gt;.scopes            optional override of the default scopes (comma or space separated)
 * app.mcp.oauth.providers.&lt;id&gt;.mcp-url           optional override of the remote MCP endpoint registered on connect
 * app.mcp.oauth.providers.microsoft-graph.tenant  optional Entra tenant segment, default "common"
 * </pre>
 *
 * <p>A provider is {@link Provider#configured() configured} only when the master
 * switch is on, the provider is enabled, a client id AND secret are present and
 * a usable callback base URL AND console redirect URL are set. Anything else is reported verbatim as a
 * {@link Provider#notConfiguredReason() reason} so the UI can say "not configured
 * by your admin" instead of showing a Connect button that cannot work.
 *
 * <p>The redirect URI registered with each provider's OAuth app must be exactly
 * {@code <callback-base-url>/oauth/mcp/<id>/callback}; see {@link #redirectUriFor}.
 *
 * <p>Sources (checked 2026-10-04): Google
 * https://developers.google.com/identity/protocols/oauth2/web-server and
 * https://developers.google.com/identity/protocols/oauth2/scopes; GitHub
 * https://docs.github.com/en/apps/oauth-apps/building-oauth-apps/authorizing-oauth-apps;
 * Slack https://docs.slack.dev/authentication/installing-with-oauth ,
 * .../using-token-rotation , .../reference/methods/auth.revoke; Notion
 * https://developers.notion.com/docs/authorization and
 * https://developers.notion.com/reference/revoke-token; Linear
 * https://linear.app/developers/oauth-2-0-authentication; HubSpot
 * https://developers.hubspot.com/docs/api/oauth/tokens; Microsoft
 * https://learn.microsoft.com/en-us/entra/identity-platform/v2-oauth2-auth-code-flow;
 * Asana https://developers.asana.com/docs/oauth ; Zoom
 * https://developers.zoom.us/docs/integrations/oauth/ .
 */
@Component
public class OAuthProviderRegistry {

    static final String KEY_PREFIX = "app.mcp.oauth.";
    static final String CALLBACK_PATH_PREFIX = "/oauth/mcp/";
    static final String CALLBACK_PATH_SUFFIX = "/callback";

    private static final String GOOGLE_AUTH = "https://accounts.google.com/o/oauth2/v2/auth";
    private static final String GOOGLE_TOKEN = "https://oauth2.googleapis.com/token";
    private static final String GOOGLE_REVOKE = "https://oauth2.googleapis.com/revoke";
    private static final String GOOGLE_DOCS = "https://developers.google.com/identity/protocols/oauth2/web-server";
    private static final String GSCOPE = "https://www.googleapis.com/auth/";

    /** Declaration order is the display order. */
    private static final List<OAuthProviderSpec> BUILT_IN = List.of(
        // DEFAULT SCOPES ARE READ-ONLY on purpose: once a token is attached to an MCP server an agent acts as
        // the user. A deployment that wants write access widens them with providers.<id>.scopes.
        //
        // Google: one OAuth client, four scope sets exposed as four separately connectable products.
        // access_type=offline + prompt=consent make Google return a refresh token on every consent.
        google("google-sheets", "Google Sheets", GSCOPE + "spreadsheets.readonly"),
        google("google-drive", "Google Drive", GSCOPE + "drive.readonly"),
        // drive.readonly and gmail.readonly are Google "restricted" scopes (paid CASA assessment before the
        // client can be published), so application.yml ships both providers disabled by default.
        google("google-gmail", "Gmail", GSCOPE + "gmail.readonly"),
        google("google-calendar", "Google Calendar", GSCOPE + "calendar.readonly"),

        // GitHub OAuth App. PKCE S256 supported. Classic OAuth-app tokens do not expire; if the app has
        // "expiring user tokens" enabled the response carries refresh_token and the refresh path is used.
        // GitHub has no read-only scope for private repositories (that is the full "repo" scope), so the
        // default is the profile only; the admin adds "repo" via providers.github.scopes if wanted.
        // The default MCP URL below is GitHub's hosted server; WHETHER IT ACCEPTS an OAuth-app token (gho_...)
        // as bearer is NOT confirmed by GitHub's public docs - verify before relying on it (see McpCatalogService).
        OAuthProviderSpec.builder("github", "GitHub")
            .authorize("https://github.com/login/oauth/authorize")
            .token("https://github.com/login/oauth/access_token")
            .revoke("https://api.github.com/applications/{client_id}/token", RevokeStyle.GITHUB_DELETE, RevokeToken.ACCESS)
            .scopes(" ", "read:user")
            .pkce(true)
            .mcpUrl("https://api.githubcopilot.com/mcp/")
            .docs("https://docs.github.com/en/apps/oauth-apps/building-oauth-apps/authorizing-oauth-apps")
            .build(),

        // Slack: user token (user_scope, comma separated). No PKCE in Slack's documented flow. Tokens do not
        // expire unless the app opted in to token rotation (then refresh_token + 43200s expires_in).
        OAuthProviderSpec.builder("slack", "Slack")
            .authorize("https://slack.com/oauth/v2/authorize")
            .token("https://slack.com/api/oauth.v2.access")
            .revoke("https://slack.com/api/auth.revoke", RevokeStyle.FORM_TOKEN, RevokeToken.ACCESS)
            .scopes(",", "channels:read", "channels:history", "groups:read", "groups:history",
                "users:read", "search:read")
            .scopeParam("user_scope")
            .slackUserToken(true)
            .pkce(false)
            .docs("https://docs.slack.dev/authentication/installing-with-oauth")
            .build(),

        // Notion public integration: owner=user, HTTP Basic + JSON body at the token endpoint, capabilities are
        // configured on the integration (no scope parameter). Access tokens are refreshable via refresh_token.
        OAuthProviderSpec.builder("notion", "Notion")
            .authorize("https://api.notion.com/v1/oauth/authorize")
            .token("https://api.notion.com/v1/oauth/token")
            .revoke("https://api.notion.com/v1/oauth/revoke", RevokeStyle.JSON_TOKEN_BASIC, RevokeToken.ACCESS)
            .clientAuth(ClientAuth.BASIC_JSON)
            .authParam("owner", "user")
            .tokenHeader("Notion-Version", "2022-06-28")
            .pkce(false)
            .docs("https://developers.notion.com/docs/authorization")
            .build(),

        // Linear: comma separated scopes, PKCE supported, 24h access tokens + rotating refresh tokens.
        // Linear's remote MCP server accepts an OAuth access token as bearer (https://linear.app/docs/mcp).
        OAuthProviderSpec.builder("linear", "Linear")
            .authorize("https://linear.app/oauth/authorize")
            .token("https://api.linear.app/oauth/token")
            .revoke("https://api.linear.app/oauth/revoke", RevokeStyle.FORM_TOKEN_CLIENT_BODY, RevokeToken.PREFER_REFRESH)
            .scopes(",", "read")
            .pkce(true)
            .mcpUrl("https://mcp.linear.app/mcp")
            .docs("https://linear.app/developers/oauth-2-0-authentication")
            .build(),

        // HubSpot (v1 OAuth endpoints; HubSpot lists them as deprecated from 2027-02-16 - revisit then).
        // 30 minute access tokens, refresh via form POST, no documented PKCE.
        OAuthProviderSpec.builder("hubspot", "HubSpot")
            .authorize("https://app.hubspot.com/oauth/authorize")
            .token("https://api.hubapi.com/oauth/v1/token")
            .revoke("https://api.hubapi.com/oauth/v1/refresh-tokens/{token}", RevokeStyle.HUBSPOT_DELETE, RevokeToken.REFRESH)
            .scopes(" ", "oauth", "crm.objects.contacts.read", "crm.objects.companies.read", "crm.objects.deals.read")
            .pkce(false)
            .docs("https://developers.hubspot.com/docs/api/oauth/tokens")
            .build(),

        // Microsoft identity platform v2 (Entra). offline_access is what makes a refresh token come back.
        // Entra has no RFC 7009 revocation endpoint: disconnect only forgets the tokens locally.
        OAuthProviderSpec.builder("microsoft-graph", "Microsoft 365 (Graph)")
            .family("microsoft")
            .authorize("https://login.microsoftonline.com/{tenant}/oauth2/v2.0/authorize")
            .token("https://login.microsoftonline.com/{tenant}/oauth2/v2.0/token")
            .scopes(" ", "offline_access", "User.Read", "Mail.Read", "Calendars.Read", "Files.Read")
            .authParam("response_mode", "query")
            .pkce(true)
            .docs("https://learn.microsoft.com/en-us/entra/identity-platform/v2-oauth2-auth-code-flow")
            .build(),

        // Asana: 1 hour access tokens + refresh token, PKCE supported. The default scope set is empty
        // (an unscoped/legacy app gets full access); a scoped app sets providers.asana.scopes.
        OAuthProviderSpec.builder("asana", "Asana")
            .authorize("https://app.asana.com/-/oauth_authorize")
            .token("https://app.asana.com/-/oauth_token")
            .revoke("https://app.asana.com/-/oauth_revoke", RevokeStyle.FORM_TOKEN_CLIENT_BODY, RevokeToken.PREFER_REFRESH)
            .pkce(true)
            .docs("https://developers.asana.com/docs/oauth")
            .build(),

        // Zoom: HTTP Basic client auth, 1 hour access tokens, refresh tokens rotate on every refresh.
        // Scopes are granular and configured on the Zoom app itself, so none are requested here.
        OAuthProviderSpec.builder("zoom", "Zoom")
            .authorize("https://zoom.us/oauth/authorize")
            .token("https://zoom.us/oauth/token")
            .revoke("https://zoom.us/oauth/revoke", RevokeStyle.FORM_TOKEN_BASIC, RevokeToken.ACCESS)
            .clientAuth(ClientAuth.BASIC)
            .pkce(true)
            .docs("https://developers.zoom.us/docs/integrations/oauth/")
            .build()
    );

    private static OAuthProviderSpec google(String id, String name, String... scopes) {
        return OAuthProviderSpec.builder(id, name)
            .family("google")
            .authorize(GOOGLE_AUTH)
            .token(GOOGLE_TOKEN)
            .revoke(GOOGLE_REVOKE, RevokeStyle.FORM_TOKEN, RevokeToken.PREFER_REFRESH)
            .scopes(" ", scopes)
            .authParam("access_type", "offline")
            .authParam("prompt", "consent")
            .pkce(true)
            .docs(GOOGLE_DOCS)
            .build();
    }

    /**
     * A provider spec merged with this deployment's configuration.
     *
     * @param scopes        effective scopes (override, else the spec's defaults)
     * @param mcpUrl        remote MCP endpoint to register on connect; null/blank when none
     * @param redirectUri   the exact redirect URI this deployment sends (and must be registered with the provider); null if no usable callback base
     * @param notConfiguredReason null when configured; otherwise one of {@code feature_off}, {@code disabled}, {@code no_client}, {@code no_callback_base}, {@code no_console_redirect}
     */
    public record Provider(
        OAuthProviderSpec spec,
        String clientId,
        String clientSecret,
        List<String> scopes,
        String mcpUrl,
        String authorityTenant,
        String redirectUri,
        String notConfiguredReason
    ) {
        public boolean configured() {
            return notConfiguredReason == null;
        }

        public String id() {
            return spec.id();
        }

        /** Name this platform registers the remote MCP server under (matches the catalog entry key). */
        public String mcpServerName() {
            return spec.id() + "-oauth";
        }

        public boolean hasMcpServer() {
            return mcpUrl != null && !mcpUrl.isBlank();
        }

        /** True when {@code serverUrl} is an https URL on exactly the host of this provider's MCP endpoint - the only place its token may be sent. */
        public boolean allowsMcpServerUrl(String serverUrl) {
            if (!hasMcpServer() || serverUrl == null) {
                return false;
            }
            try {
                URI wanted = URI.create(mcpUrl.trim());
                URI actual = URI.create(serverUrl.trim());
                return "https".equalsIgnoreCase(actual.getScheme())
                    && actual.getHost() != null
                    && actual.getHost().equalsIgnoreCase(wanted.getHost());
            } catch (IllegalArgumentException e) {
                return false;
            }
        }

        /** Authorize/token URL with {@code {tenant}} resolved. */
        public String resolve(String template) {
            return template == null ? null : template.replace("{tenant}", authorityTenant);
        }
    }

    private final Function<String, String> props;
    private final boolean featureEnabled;
    private final String callbackBaseUrl;
    private final String consoleRedirectUri;
    private final Map<String, Provider> byId;

    @Autowired
    public OAuthProviderRegistry(Environment environment) {
        this(key -> environment.getProperty(key));
    }

    /** Test seam: any key lookup (e.g. a map's {@code get}). Returns null for unset keys. */
    public OAuthProviderRegistry(Function<String, String> properties) {
        this.props = properties;
        this.featureEnabled = "true".equalsIgnoreCase(trim(props.apply(KEY_PREFIX + "providers-enabled")));
        this.callbackBaseUrl = stripTrailingSlash(trim(props.apply(KEY_PREFIX + "provider-callback-base-url")));
        this.consoleRedirectUri = trim(props.apply(KEY_PREFIX + "provider-console-redirect-uri"));
        Map<String, Provider> built = new LinkedHashMap<>();
        for (OAuthProviderSpec spec : BUILT_IN) {
            built.put(spec.id(), configure(spec));
        }
        this.byId = java.util.Collections.unmodifiableMap(built);
    }

    public boolean featureEnabled() {
        return featureEnabled;
    }

    /** All providers in display order, configured or not. */
    public List<Provider> all() {
        return new ArrayList<>(byId.values());
    }

    public Optional<Provider> find(String id) {
        return id == null ? Optional.empty() : Optional.ofNullable(byId.get(id.toLowerCase(Locale.ROOT)));
    }

    /** The redirect URI this deployment sends for {@code providerId} (callback base + fixed path), or null when no base is set. */
    public static String redirectUriFor(String callbackBaseUrl, String providerId) {
        if (callbackBaseUrl == null || callbackBaseUrl.isBlank()) {
            return null;
        }
        return stripTrailingSlash(callbackBaseUrl.trim()) + CALLBACK_PATH_PREFIX + providerId + CALLBACK_PATH_SUFFIX;
    }

    private Provider configure(OAuthProviderSpec spec) {
        String base = KEY_PREFIX + "providers." + spec.id() + ".";
        String clientId = trim(props.apply(base + "client-id"));
        String clientSecret = trim(props.apply(base + "client-secret"));
        String enabledRaw = trim(props.apply(base + "enabled"));
        boolean providerEnabled = enabledRaw.isEmpty() || "true".equalsIgnoreCase(enabledRaw);
        List<String> scopes = parseScopes(props.apply(base + "scopes"), spec.defaultScopes());
        String mcpOverride = trim(props.apply(base + "mcp-url"));
        String mcpUrl = mcpOverride.isEmpty() ? spec.defaultMcpUrl() : mcpOverride;
        String tenantRaw = trim(props.apply(base + "tenant"));
        String tenant = tenantRaw.isEmpty() ? "common" : tenantRaw;
        String redirectUri = redirectUriFor(callbackBaseUrl, spec.id());

        String reason = null;
        if (!featureEnabled) {
            reason = "feature_off";
        } else if (!providerEnabled) {
            reason = "disabled";
        } else if (clientId.isEmpty() || clientSecret.isEmpty()) {
            reason = "no_client";
        } else if (redirectUri == null || !OAuthRedirectPolicy.isAcceptableRedirectUri(redirectUri)) {
            reason = "no_callback_base";
        } else if (!consoleRedirectUsable(consoleRedirectUri, redirectUri)) {
            reason = "no_console_redirect";
        }
        return new Provider(spec, clientId, clientSecret, scopes, mcpUrl, tenant, redirectUri, reason);
    }

    /**
     * The console landing page is browser-facing (RULE 0: never localhost on a real deployment). It must be an
     * http(s) URL; a loopback one is accepted only on a developer machine, i.e. when the callback base is loopback too.
     */
    static boolean consoleRedirectUsable(String consoleUri, String providerRedirectUri) {
        String origin = OAuthRedirectPolicy.originOf(consoleUri);
        if (origin == null) {
            return false;
        }
        return !isLoopbackOrigin(origin) || isLoopbackOrigin(OAuthRedirectPolicy.originOf(providerRedirectUri));
    }

    private static boolean isLoopbackOrigin(String origin) {
        if (origin == null) {
            return false;
        }
        String host = URI.create(origin).getHost();
        if (host == null) {
            return false;
        }
        String h = host.toLowerCase(Locale.ROOT);
        return "localhost".equals(h) || "127.0.0.1".equals(h) || "[::1]".equals(h) || "::1".equals(h);
    }

    /** Comma and/or whitespace separated; blank or null yields {@code defaults}. */
    static List<String> parseScopes(String raw, List<String> defaults) {
        if (raw == null || raw.isBlank()) {
            return defaults;
        }
        List<String> out = new ArrayList<>();
        for (String s : raw.trim().split("[,\\s]+")) {
            if (!s.isEmpty() && !out.contains(s)) {
                out.add(s);
            }
        }
        return out.isEmpty() ? defaults : List.copyOf(out);
    }

    private static String trim(String s) {
        return s == null ? "" : s.trim();
    }

    private static String stripTrailingSlash(String s) {
        String out = s;
        while (out.endsWith("/")) {
            out = out.substring(0, out.length() - 1);
        }
        return out;
    }
}
