package com.agenticai.core.mcp.oauth;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * URL canonicalisation and resource-matching rules of the generic MCP OAuth flow, as pure functions.
 *
 * <p>The canonical form is the identity of a connection ({@code mcp_oauth_connection.resource_url}) and the
 * value of the RFC 8707 {@code resource} parameter: lower-case scheme and host, default port dropped,
 * trailing slashes dropped, no fragment, no credentials. Two spellings of one server must not produce two
 * connections, and - the security half - a token minted for one resource must never be released to a URL
 * that is not covered by it.
 */
public final class McpOAuthUrls {

    private McpOAuthUrls() {
    }

    /**
     * @return the canonical https URL
     * @throws McpOAuthException not an absolute https URL, or it carries a fragment or credentials
     */
    public static String canonical(String url) {
        if (url == null || url.isBlank()) {
            throw new McpOAuthException("The MCP server URL is empty.");
        }
        URI uri;
        try {
            uri = new URI(url.trim());
        } catch (Exception e) {
            throw new McpOAuthException("The MCP server URL is not a valid URL.");
        }
        if (uri.getScheme() == null || !"https".equalsIgnoreCase(uri.getScheme())) {
            throw new McpOAuthException("OAuth sign-in needs an https MCP server URL.");
        }
        if (uri.getRawUserInfo() != null || uri.getRawFragment() != null) {
            throw new McpOAuthException("The MCP server URL must not contain credentials or a fragment.");
        }
        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            throw new McpOAuthException("The MCP server URL has no hostname.");
        }
        StringBuilder out = new StringBuilder("https://").append(host.toLowerCase(Locale.ROOT));
        int port = uri.getPort();
        if (port != -1 && port != 443) {
            out.append(':').append(port);
        }
        String path = uri.getRawPath() == null ? "" : uri.getRawPath();
        while (path.endsWith("/")) {
            path = path.substring(0, path.length() - 1);
        }
        out.append(path);
        if (uri.getRawQuery() != null) {
            out.append('?').append(uri.getRawQuery());
        }
        return out.toString();
    }

    /**
     * RFC 8707 / RFC 9728 audience match: {@code resource} covers {@code serverUrl} when both are the same
     * origin and the resource's path is the server URL's path or a path-segment prefix of it
     * (resource {@code https://h/} covers {@code https://h/mcp}; {@code https://h/mcp} does not cover
     * {@code https://h/mcp-evil} or {@code https://h/other}).
     */
    public static boolean covers(String resource, String serverUrl) {
        try {
            URI r = URI.create(canonical(resource));
            URI s = URI.create(canonical(serverUrl));
            if (!r.getRawAuthority().equals(s.getRawAuthority())) {
                return false;
            }
            String rp = r.getRawPath() == null ? "" : r.getRawPath();
            String sp = s.getRawPath() == null ? "" : s.getRawPath();
            if (!sp.equals(rp) && !sp.startsWith(rp + "/")) {
                return false;
            }
            // A resource that names a query must be matched exactly; a query-less resource covers the path.
            return r.getRawQuery() == null || r.getRawQuery().equals(s.getRawQuery());
        } catch (McpOAuthException | IllegalArgumentException e) {
            return false;
        }
    }

    /**
     * The canonical URL itself followed by each ancestor path, down to the origin - the candidate resource
     * keys a stored connection for {@code serverUrl} could live under, most specific first.
     */
    public static List<String> candidateResources(String serverUrl) {
        String canonical = canonical(serverUrl);
        List<String> out = new ArrayList<>();
        out.add(canonical);
        URI uri = URI.create(canonical);
        String origin = "https://" + uri.getRawAuthority();
        String path = uri.getRawPath() == null ? "" : uri.getRawPath();
        while (!path.isEmpty()) {
            int slash = path.lastIndexOf('/');
            path = slash <= 0 ? "" : path.substring(0, slash);
            String candidate = origin + path;
            if (!out.contains(candidate)) {
                out.add(candidate);
            }
        }
        return out;
    }
}
