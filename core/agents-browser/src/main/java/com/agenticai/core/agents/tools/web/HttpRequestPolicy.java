package com.agenticai.core.agents.tools.web;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * The per-call guard rails of the hardened ("safe mode") {@code http_request} tools, resolved once
 * per agent by {@link HttpRequestSafetyConfig#policyFor}. {@link HttpRequestExecutor#executeSafe}
 * is the only consumer; the legacy {@link HttpRequestExecutor#execute(HttpRequestSpec, String)}
 * path takes no policy and behaves exactly as it always has.
 *
 * @param allowedMethods upper-case HTTP verbs this tool instance may send (the read tool and the
 *     write tool are separate tool names so the classification gate can tell them apart); empty =
 *     any
 * @param allowHttp false (the default) refuses plain {@code http://} on every hop, so a redirect
 *     cannot downgrade the transport either
 * @param maxRequestBodyBytes UTF-8 byte cap on the request body
 * @param maxResponseBytes cap on the raw response read; the rest is dropped and the body is marked
 *     truncated
 * @param maxRedirects redirects followed by hand, each re-validated (SSRF + allow-list)
 * @param allowedContentTypes lower-case media-type patterns ({@code application/json}, {@code
 *     text/*}, {@code application/*+json}) accepted for both the request {@code Content-Type} and
 *     the response; empty = any. A response without a Content-Type is not rejected on this ground
 * @param deniedHeaders lower-case header names a model may not set directly (credential-bearing
 *     ones: Authorization, Cookie, ...); credentials are injected only from a named vault secret
 *     via {@link HttpRequestCredential}
 * @param globalAllowedHosts operator allow-list, a ceiling for every agent; empty = no ceiling
 * @param agentAllowedHosts this agent's own allow-list; empty = no per-agent restriction
 * @param credentialsRequireAllowlist true: a request carrying a named credential is refused unless
 *     at least one of the two allow-lists is configured, so a prompt-injected URL cannot receive a
 *     stored secret by being "any public host"
 */
public record HttpRequestPolicy(
        Set<String> allowedMethods,
        boolean allowHttp,
        int maxRequestBodyBytes,
        int maxResponseBytes,
        int maxRedirects,
        List<String> allowedContentTypes,
        Set<String> deniedHeaders,
        List<String> globalAllowedHosts,
        List<String> agentAllowedHosts,
        boolean credentialsRequireAllowlist) {

    public HttpRequestPolicy {
        allowedMethods = allowedMethods == null ? Set.of() : Set.copyOf(allowedMethods);
        allowedContentTypes =
                allowedContentTypes == null ? List.of() : List.copyOf(allowedContentTypes);
        deniedHeaders = deniedHeaders == null ? Set.of() : Set.copyOf(deniedHeaders);
        globalAllowedHosts =
                globalAllowedHosts == null ? List.of() : List.copyOf(globalAllowedHosts);
        agentAllowedHosts = agentAllowedHosts == null ? List.of() : List.copyOf(agentAllowedHosts);
        maxRequestBodyBytes = Math.max(0, maxRequestBodyBytes);
        maxResponseBytes = Math.max(1_024, maxResponseBytes);
        maxRedirects = Math.max(0, Math.min(maxRedirects, 5));
    }

    /**
     * A copy of this policy restricted to {@code methods} (used to split the read and write tools).
     */
    public HttpRequestPolicy withMethods(Set<String> methods) {
        return new HttpRequestPolicy(
                methods,
                allowHttp,
                maxRequestBodyBytes,
                maxResponseBytes,
                maxRedirects,
                allowedContentTypes,
                deniedHeaders,
                globalAllowedHosts,
                agentAllowedHosts,
                credentialsRequireAllowlist);
    }

    public boolean methodAllowed(String upperCaseMethod) {
        return allowedMethods.isEmpty() || allowedMethods.contains(upperCaseMethod);
    }

    /**
     * True when either allow-list has at least one entry. An empty pair means "any public host" -
     * honestly no restriction.
     */
    public boolean allowlistConfigured() {
        return !globalAllowedHosts.isEmpty() || !agentAllowedHosts.isEmpty();
    }

    /**
     * A host passes when it matches every configured list (the operator list is a ceiling, the
     * agent list a narrowing); with neither configured every host passes (the SSRF guard still
     * refuses non-public addresses).
     */
    public boolean hostAllowed(String host) {
        if (host == null || host.isBlank()) {
            return false;
        }
        String h = stripBrackets(host).toLowerCase(Locale.ROOT);
        return matchesAll(globalAllowedHosts, h) && matchesAll(agentAllowedHosts, h);
    }

    private static boolean matchesAll(List<String> list, String host) {
        if (list.isEmpty()) {
            return true;
        }
        for (String entry : list) {
            if (entry == null || entry.isBlank()) {
                continue;
            }
            String e = entry.strip().toLowerCase(Locale.ROOT);
            if (e.startsWith("*.")) {
                // "*.example.com" matches "a.example.com", never "example.com" itself.
                if (host.endsWith(e.substring(1))) {
                    return true;
                }
            } else if (host.equals(e)) {
                return true;
            }
        }
        return false;
    }

    private static String stripBrackets(String host) {
        return host.startsWith("[") && host.endsWith("]")
                ? host.substring(1, host.length() - 1)
                : host;
    }

    public boolean headerDenied(String headerName) {
        return headerName != null
                && deniedHeaders.contains(headerName.strip().toLowerCase(Locale.ROOT));
    }

    /**
     * Blank/absent content type is accepted; otherwise it must match an allowed pattern (or no
     * patterns are set).
     */
    public boolean contentTypeAllowed(String contentType) {
        if (allowedContentTypes.isEmpty() || contentType == null || contentType.isBlank()) {
            return true;
        }
        String mediaType = contentType.toLowerCase(Locale.ROOT);
        int semi = mediaType.indexOf(';');
        if (semi >= 0) {
            mediaType = mediaType.substring(0, semi);
        }
        mediaType = mediaType.strip();
        for (String pattern : allowedContentTypes) {
            if (patternMatches(pattern, mediaType)) {
                return true;
            }
        }
        return false;
    }

    /** {@code text/*} (any subtype), {@code application/*+json} (suffix) or an exact type. */
    private static boolean patternMatches(String rawPattern, String mediaType) {
        if (rawPattern == null || rawPattern.isBlank()) {
            return false;
        }
        String pattern = rawPattern.strip().toLowerCase(Locale.ROOT);
        int star = pattern.indexOf('*');
        if (star < 0) {
            return pattern.equals(mediaType);
        }
        String prefix = pattern.substring(0, star);
        String suffix = pattern.substring(star + 1);
        return mediaType.length() >= prefix.length() + suffix.length()
                && mediaType.startsWith(prefix)
                && mediaType.endsWith(suffix);
    }

    /** Splits a comma/space separated config value into trimmed lower-case entries. */
    static List<String> splitList(String csv) {
        List<String> out = new ArrayList<>();
        if (csv == null) {
            return out;
        }
        for (String part : csv.split("[,\\s]+")) {
            if (!part.isBlank()) {
                out.add(part.strip().toLowerCase(Locale.ROOT));
            }
        }
        return out;
    }
}
