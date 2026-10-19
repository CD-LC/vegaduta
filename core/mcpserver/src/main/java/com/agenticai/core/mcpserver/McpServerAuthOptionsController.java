package com.agenticai.core.mcpserver;

import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Read-only description of how callers can authenticate to {@code /mcp} on this deployment, for the
 * console's MCP access page: grant keys (always), the optional OAuth 2.1 mode and the Origin check.
 * Describes configuration only; no secret is involved (the OAuth settings are public by design,
 * they are what the protected-resource metadata publishes). Admin-only, see {@code AdminIntegrationRules}.
 */
@RestController
@Profile("mcpserver")
@RequestMapping("/api/mcpserver/grants/auth-options")
public class McpServerAuthOptionsController {

    public record OAuthOptions(
            boolean enabled, String resource, String metadataUrl, String issuer,
            String tenantClaim, String grantClaim, List<String> scopes) {}

    public record OriginOptions(String mode, List<String> allowed) {}

    public record AuthOptions(boolean grantKeys, OAuthOptions oauth, OriginOptions origin) {}

    private final boolean oauthEnabled;
    private final String resource;
    private final String issuer;
    private final String tenantClaim;
    private final String grantClaim;
    private final String originMode;
    private final String originAllowed;

    public McpServerAuthOptionsController(
            @Value("${app.mcpserver.oauth.enabled:false}") boolean oauthEnabled,
            @Value("${app.mcpserver.oauth.resource:}") String resource,
            @Value("${app.mcpserver.oauth.issuer:}") String issuer,
            @Value("${app.mcpserver.oauth.tenant-claim:tenant_id}") String tenantClaim,
            @Value("${app.mcpserver.oauth.grant-claim:mcp_grant_id}") String grantClaim,
            @Value("${app.mcpserver.origin.mode:enforce}") String originMode,
            @Value("${app.mcpserver.origin.allowed:}") String originAllowed) {
        this.oauthEnabled = oauthEnabled;
        this.resource = resource;
        this.issuer = issuer;
        this.tenantClaim = tenantClaim;
        this.grantClaim = grantClaim;
        this.originMode = originMode;
        this.originAllowed = originAllowed;
    }

    @GetMapping
    public AuthOptions get() {
        String metadataUrl = oauthEnabled && !resource.isBlank()
                ? McpProtectedResourceMetadataFilter.metadataUrl(resource) : null;
        OAuthOptions oauth = new OAuthOptions(
                oauthEnabled, oauthEnabled ? resource : null, metadataUrl, oauthEnabled ? issuer : null,
                tenantClaim, grantClaim, McpOAuthScopes.SUPPORTED);
        List<String> allowed = java.util.Arrays.stream(originAllowed.split(","))
                .map(String::trim).filter(s -> !s.isEmpty()).toList();
        return new AuthOptions(true, oauth, new OriginOptions(
                McpOriginValidationFilter.Mode.parse(originMode).name().toLowerCase(java.util.Locale.ROOT), allowed));
    }
}
