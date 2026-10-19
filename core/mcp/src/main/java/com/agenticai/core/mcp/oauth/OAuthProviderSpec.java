package com.agenticai.core.mcp.oauth;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Static description of ONE well-known SaaS OAuth 2.0 authorization-code
 * provider (endpoints, default scopes, wire quirks). Pure data: client id /
 * secret, enabled flag and per-deployment overrides live in
 * {@link OAuthProviderRegistry}, never here, and no endpoint in this table can
 * be changed through configuration (only the credentials can), so a tenant or
 * an admin typo cannot point the token exchange at an arbitrary host.
 *
 * <p>Every endpoint below was checked against the provider's own documentation
 * (URLs are cited next to each entry in {@link OAuthProviderRegistry}).
 *
 * @param id             stable lower-case identifier used in URLs, config keys and the MCP-registration marker (max 20 chars: the marker column is VARCHAR(32))
 * @param family         groups variants of one vendor in the UI (all Google products share {@code "google"})
 * @param authorizeUrl   may contain {@code {tenant}} (Microsoft)
 * @param revokeUrl      null when the provider has no token-revocation endpoint; may contain {@code {client_id}} / {@code {token}}
 * @param scopeParam     query parameter that carries the scopes ({@code scope}, or Slack's {@code user_scope})
 * @param scopeSeparator how the scopes are joined ({@code " "} or {@code ","})
 * @param extraAuthParams fixed extra query parameters on the authorize URL (e.g. Google {@code access_type=offline})
 * @param tokenHeaders   fixed extra headers on token/refresh calls
 * @param slackUserToken true when the access token lives under {@code authed_user} in the exchange response (Slack user scopes)
 * @param defaultMcpUrl  the provider's official remote MCP endpoint when this platform registers one on connect; null otherwise
 */
public record OAuthProviderSpec(
    String id,
    String name,
    String family,
    String authorizeUrl,
    String tokenUrl,
    String revokeUrl,
    List<String> defaultScopes,
    String scopeParam,
    String scopeSeparator,
    boolean pkce,
    ClientAuth clientAuth,
    Map<String, String> extraAuthParams,
    Map<String, String> tokenHeaders,
    boolean slackUserToken,
    RevokeStyle revokeStyle,
    RevokeToken revokeToken,
    String defaultMcpUrl,
    String docsUrl
) {

    /** How this platform authenticates itself to the token endpoint. */
    public enum ClientAuth {
        /** {@code client_id}/{@code client_secret} as form fields. */
        BODY,
        /** HTTP Basic, form-encoded body. */
        BASIC,
        /** HTTP Basic, JSON body (Notion). */
        BASIC_JSON
    }

    /** Which wire shape the provider's revocation call uses. */
    public enum RevokeStyle {
        NONE,
        /** POST form {@code token=} only (Google, Slack). */
        FORM_TOKEN,
        /** POST form {@code token=} plus client credentials in the body (Linear, Asana). */
        FORM_TOKEN_CLIENT_BODY,
        /** POST form {@code token=} with HTTP Basic client auth (Zoom). */
        FORM_TOKEN_BASIC,
        /** POST JSON {@code {"token":...}} with HTTP Basic client auth (Notion). */
        JSON_TOKEN_BASIC,
        /** DELETE {@code /applications/{client_id}/token} with Basic auth and JSON body (GitHub). */
        GITHUB_DELETE,
        /** DELETE {@code /oauth/v1/refresh-tokens/{token}} (HubSpot). */
        HUBSPOT_DELETE
    }

    /** Which of the stored tokens a revocation call needs. */
    public enum RevokeToken {
        ACCESS,
        REFRESH,
        PREFER_REFRESH
    }

    public static Builder builder(String id, String name) {
        return new Builder(id, name);
    }

    /** Fluent builder so the 18 components of the table below are always set by NAME, never by position. */
    public static final class Builder {
        private final String id;
        private final String name;
        private String family;
        private String authorizeUrl;
        private String tokenUrl;
        private String revokeUrl;
        private List<String> defaultScopes = List.of();
        private String scopeParam = "scope";
        private String scopeSeparator = " ";
        private boolean pkce = true;
        private ClientAuth clientAuth = ClientAuth.BODY;
        private final Map<String, String> extraAuthParams = new LinkedHashMap<>();
        private final Map<String, String> tokenHeaders = new LinkedHashMap<>();
        private boolean slackUserToken;
        private RevokeStyle revokeStyle = RevokeStyle.NONE;
        private RevokeToken revokeToken = RevokeToken.ACCESS;
        private String defaultMcpUrl;
        private String docsUrl;

        private Builder(String id, String name) {
            this.id = id;
            this.name = name;
            this.family = id;
        }

        public Builder family(String v) {
            this.family = v;
            return this;
        }

        public Builder authorize(String v) {
            this.authorizeUrl = v;
            return this;
        }

        public Builder token(String v) {
            this.tokenUrl = v;
            return this;
        }

        public Builder revoke(String url, RevokeStyle style, RevokeToken which) {
            this.revokeUrl = url;
            this.revokeStyle = style;
            this.revokeToken = which;
            return this;
        }

        public Builder scopes(String separator, String... scopes) {
            this.scopeSeparator = separator;
            this.defaultScopes = List.of(scopes);
            return this;
        }

        public Builder scopeParam(String v) {
            this.scopeParam = v;
            return this;
        }

        public Builder pkce(boolean v) {
            this.pkce = v;
            return this;
        }

        public Builder clientAuth(ClientAuth v) {
            this.clientAuth = v;
            return this;
        }

        public Builder authParam(String key, String value) {
            this.extraAuthParams.put(key, value);
            return this;
        }

        public Builder tokenHeader(String key, String value) {
            this.tokenHeaders.put(key, value);
            return this;
        }

        public Builder slackUserToken(boolean v) {
            this.slackUserToken = v;
            return this;
        }

        public Builder mcpUrl(String v) {
            this.defaultMcpUrl = v;
            return this;
        }

        public Builder docs(String v) {
            this.docsUrl = v;
            return this;
        }

        public OAuthProviderSpec build() {
            return new OAuthProviderSpec(
                id, name, family, authorizeUrl, tokenUrl, revokeUrl, defaultScopes, scopeParam, scopeSeparator, pkce,
                clientAuth, java.util.Collections.unmodifiableMap(new LinkedHashMap<>(extraAuthParams)),
                java.util.Collections.unmodifiableMap(new LinkedHashMap<>(tokenHeaders)), slackUserToken, revokeStyle,
                revokeToken, defaultMcpUrl, docsUrl);
        }
    }
}
