package com.agenticai.core.mcpserver;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URI;
import java.util.List;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Serves this MCP server's OAuth 2.0 Protected Resource Metadata (RFC 9728), which the MCP
 * authorization spec requires of any server that supports authorization: the document names the
 * resource's canonical URI, the authorization server(s) a client should obtain tokens from, and the
 * (wildcard-free) scopes supported. Public, GET only, no credentials involved.
 */
class McpProtectedResourceMetadataFilter extends OncePerRequestFilter {

    private static final ObjectMapper MAPPER = com.agenticai.core.json.JsonMappers.plain();

    private final String resource;
    private final List<String> authorizationServers;
    private final String json;

    McpProtectedResourceMetadataFilter(String resource, List<String> authorizationServers) {
        this.resource = resource;
        this.authorizationServers = authorizationServers;
        ObjectNode doc = MAPPER.createObjectNode();
        doc.put("resource", resource);
        ArrayNode servers = doc.putArray("authorization_servers");
        authorizationServers.forEach(servers::add);
        ArrayNode scopes = doc.putArray("scopes_supported");
        McpOAuthScopes.SUPPORTED.forEach(scopes::add);
        doc.putArray("bearer_methods_supported").add("header");
        doc.put("resource_name", "VegaDuta MCP server");
        this.json = doc.toString();
    }

    /**
     * The RFC 9728 well-known URL for {@code resource}: path inserted after the well-known segment.
     */
    static String metadataUrl(String resource) {
        URI uri = URI.create(resource);
        String path = uri.getRawPath() == null ? "" : uri.getRawPath();
        if (path.endsWith("/")) {
            path = path.substring(0, path.length() - 1);
        }
        String authority = uri.getRawAuthority();
        return uri.getScheme() + "://" + authority + "/.well-known/oauth-protected-resource" + path;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (!"GET".equalsIgnoreCase(request.getMethod())) {
            response.setStatus(HttpServletResponse.SC_METHOD_NOT_ALLOWED);
            response.setHeader("Allow", "GET");
            return;
        }
        response.setStatus(HttpServletResponse.SC_OK);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setHeader("Cache-Control", "public, max-age=300");
        response.getWriter().write(json);
    }

    String resource() {
        return resource;
    }

    List<String> authorizationServers() {
        return authorizationServers;
    }
}
