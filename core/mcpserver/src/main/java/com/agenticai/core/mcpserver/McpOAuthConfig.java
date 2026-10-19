package com.agenticai.core.mcpserver;

import java.net.URI;
import java.util.Arrays;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/**
 * OAuth 2.1 option for the {@code /mcp} server ({@code app.mcpserver.oauth.enabled}, default false),
 * next to the {@code vmcp_} grant keys, which keep working unchanged and remain the simple option.
 * With it on, the server publishes RFC 9728 Protected Resource Metadata, answers an unauthenticated
 * request with {@code WWW-Authenticate: Bearer resource_metadata=...}, and accepts access tokens
 * issued by the configured authorization server for exactly this resource (see
 * {@link McpOAuthAuthenticator}). Misconfiguration fails startup instead of leaving a half-secured
 * endpoint.
 */
@Configuration
@Profile("mcpserver")
@ConditionalOnProperty(name = "app.mcpserver.oauth.enabled", havingValue = "true")
public class McpOAuthConfig {

    @Bean
    McpOAuthAuthenticator mcpOAuthAuthenticator(
        @Value("${app.mcpserver.oauth.resource:}") String resource,
        @Value("${app.mcpserver.oauth.issuer:}") String issuer,
        @Value("${app.mcpserver.oauth.jwk-set-uri:}") String jwkSetUri,
        @Value("${app.mcpserver.oauth.tenant-claim:tenant_id}") String tenantClaim,
        @Value("${app.mcpserver.oauth.grant-claim:mcp_grant_id}") String grantClaim
    ) {
        requireHttpsOrLocal(resource, "app.mcpserver.oauth.resource");
        requireHttpsOrLocal(issuer, "app.mcpserver.oauth.issuer");
        requireHttpsOrLocal(jwkSetUri, "app.mcpserver.oauth.jwk-set-uri");
        return McpOAuthAuthenticator.forJwks(jwkSetUri, issuer, resource, tenantClaim, grantClaim);
    }

    /** RFC 9728 metadata, served at the well-known URI for the resource (and under /mcp for simple proxies). */
    @Bean
    FilterRegistrationBean<McpProtectedResourceMetadataFilter> mcpProtectedResourceMetadataRegistration(
        @Value("${app.mcpserver.oauth.resource:}") String resource,
        @Value("${app.mcpserver.oauth.authorization-servers:}") String authorizationServers,
        @Value("${app.mcpserver.oauth.issuer:}") String issuer
    ) {
        // Default: the one issuer whose tokens we validate is the authorization server clients are pointed to.
        String advertised = authorizationServers.isBlank() ? issuer : authorizationServers;
        List<String> servers = Arrays.stream(advertised.split(",")).map(String::trim)
            .filter(s -> !s.isEmpty()).toList();
        if (servers.isEmpty()) {
            throw new IllegalStateException("app.mcpserver.oauth.enabled=true needs app.mcpserver.oauth.issuer or .authorization-servers");
        }
        FilterRegistrationBean<McpProtectedResourceMetadataFilter> registration =
            new FilterRegistrationBean<>(new McpProtectedResourceMetadataFilter(resource, servers));
        registration.addUrlPatterns("/.well-known/oauth-protected-resource", "/.well-known/oauth-protected-resource/*",
            "/mcp/.well-known/oauth-protected-resource");
        registration.setOrder(40);
        registration.setName("mcpProtectedResourceMetadataFilter");
        return registration;
    }

    /** Spec: authorization-server endpoints and the resource identifier are HTTPS (loopback excepted for dev). */
    static void requireHttpsOrLocal(String value, String property) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(property + " is required when app.mcpserver.oauth.enabled=true");
        }
        URI uri = URI.create(value.trim());
        String host = uri.getHost() == null ? "" : uri.getHost();
        boolean local = host.equals("localhost") || host.equals("127.0.0.1") || host.equals("[::1]") || host.endsWith(".localhost");
        if (!"https".equalsIgnoreCase(uri.getScheme()) && !(local && "http".equalsIgnoreCase(uri.getScheme()))) {
            throw new IllegalStateException(property + " must be an https URL (got '" + value + "')");
        }
        if (uri.getFragment() != null) {
            throw new IllegalStateException(property + " must not contain a fragment");
        }
    }
}
