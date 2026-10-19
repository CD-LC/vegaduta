package com.agenticai.core.agents.tools.browseruse;

import java.net.IDN;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Operator-level and per-agent navigation allow/deny lists for the browser tools ({@code
 * app.browser.allowed-domains}, {@code blocked-domains}, {@code agent-allowed-domains}, {@code
 * agent-blocked-domains}). Pure and immutable: no Playwright, no Spring, so the matching rules
 * (exact, {@code *.suffix}, redirect hops, IDN/punycode, the userinfo trick) are unit-testable.
 *
 * <h2>Semantics (mirrors {@code HttpRequestPolicy#hostAllowed} on purpose)</h2>
 *
 * <ul>
 *   <li>An entry is an exact host ({@code example.com}) or a subdomain wildcard ({@code
 *       *.example.com}, which matches {@code a.example.com} and {@code a.b.example.com} but NOT
 *       {@code example.com} itself - list both when both are wanted). A bare {@code *} matches
 *       every host. Entries may be written as full URLs; only the host is kept. Unicode entries are
 *       punycoded, so {@code bücher.de} and {@code xn--bcher-kva.de} are the same entry.
 *   <li>The operator allow-list is a ceiling, the agent allow-list a narrowing: when both are
 *       configured a host must match both. Empty list = no restriction from that list.
 *   <li>Blocked entries (operator and agent, unioned) always win over allowed ones.
 *   <li>The allow-list is enforced on DOCUMENT requests (top-level navigations, every redirect hop,
 *       popups, iframes). Sub-resources (images, scripts, XHR, fonts) are not subject to it - a
 *       page on an allowed domain legitimately loads assets from CDNs - but ARE subject to the
 *       blocked list. This is stated plainly because it is the honest boundary of the control: it
 *       governs where the browser may go, not every host the visited page talks to.
 *   <li>Fail closed: a URL that cannot be parsed, has embedded credentials ({@code
 *       https://allowed.com@evil.com/}), has no host or a host that cannot be IDN-normalised is
 *       refused whenever any list is configured.
 * </ul>
 *
 * With no list configured ({@link #isActive()} false) every URL passes and the caller behaves
 * exactly as it did before this class existed (the SSRF guard in {@link BrowserUseUrlValidator} is
 * separate and always runs).
 */
final class BrowserDomainPolicy {

    private static final Logger log = LoggerFactory.getLogger(BrowserDomainPolicy.class);

    private static final java.util.regex.Pattern LDH_HOST =
            java.util.regex.Pattern.compile("[a-z0-9_.-]+");
    private static final java.util.regex.Pattern IPV6_HOST =
            java.util.regex.Pattern.compile("[0-9a-f:.]+");

    /** No lists configured: everything passes. */
    static final BrowserDomainPolicy INACTIVE =
            new BrowserDomainPolicy(List.of(), List.of(), Map.of(), Map.of());

    /**
     * The outcome of one check. {@code message} is model-facing and never contains the URL's path
     * or query.
     */
    record Decision(boolean allowed, String host, String message) {

        static Decision allow(String host) {
            return new Decision(true, host, null);
        }

        static Decision deny(String host, String message) {
            return new Decision(false, host, message);
        }
    }

    private final List<String> allowed;
    private final List<String> blocked;
    private final Map<UUID, List<String>> agentAllowed;
    private final Map<UUID, List<String>> agentBlocked;

    BrowserDomainPolicy(
            List<String> allowed,
            List<String> blocked,
            Map<UUID, List<String>> agentAllowed,
            Map<UUID, List<String>> agentBlocked) {
        this.allowed = normalizeEntries(allowed);
        this.blocked = normalizeEntries(blocked);
        this.agentAllowed = normalizeMap(agentAllowed);
        this.agentBlocked = normalizeMap(agentBlocked);
    }

    /**
     * Builds a policy from the four raw config strings (see class javadoc); malformed per-agent
     * entries are skipped with a warning.
     */
    static BrowserDomainPolicy fromConfig(
            String allowed, String blocked, String agentAllowed, String agentBlocked) {
        return new BrowserDomainPolicy(
                parseList(allowed),
                parseList(blocked),
                parseAgentMap(agentAllowed),
                parseAgentMap(agentBlocked));
    }

    /**
     * True when at least one list has an entry. False = today's behaviour, nothing here is
     * consulted.
     */
    boolean isActive() {
        return !allowed.isEmpty()
                || !blocked.isEmpty()
                || !agentAllowed.isEmpty()
                || !agentBlocked.isEmpty();
    }

    /**
     * True when the operator list or this agent's list restricts where a document may load - the
     * precondition for letting a vault secret be typed on a page.
     */
    boolean hasAllowList(UUID agentId) {
        return !allowed.isEmpty()
                || (agentId != null && !agentAllowed.getOrDefault(agentId, List.of()).isEmpty());
    }

    /** Check for a top-level navigation / document request ({@code documentRequest = true}). */
    Decision check(UUID agentId, String url) {
        return check(agentId, url, true);
    }

    /**
     * @param documentRequest true for navigations, redirect hops, popups and iframes (allow-list
     *     applies); false for sub-resources (only the blocked list applies)
     */
    Decision check(UUID agentId, String url, boolean documentRequest) {
        if (!isActive()) {
            return Decision.allow(null);
        }
        HostResult parsed = parseHost(url);
        if (parsed.host() == null) {
            return Decision.deny(
                    null,
                    "navigation was blocked by this workspace's browser domain policy: "
                            + parsed.problem());
        }
        String host = parsed.host();
        if (hostMatches(blocked, host)
                || (agentId != null
                        && hostMatches(agentBlocked.getOrDefault(agentId, List.of()), host))) {
            return Decision.deny(
                    host,
                    "navigation to '"
                            + host
                            + "' was blocked: that domain is on the blocked-domains list "
                            + "for this workspace. Do not retry it or look for a way around the block - tell the user it was blocked.");
        }
        if (documentRequest) {
            List<String> agentList =
                    agentId == null ? List.of() : agentAllowed.getOrDefault(agentId, List.of());
            boolean ok =
                    (allowed.isEmpty() || hostMatches(allowed, host))
                            && (agentList.isEmpty() || hostMatches(agentList, host));
            if (!ok) {
                List<String> shown = agentList.isEmpty() ? allowed : agentList;
                return Decision.deny(
                        host,
                        "navigation to '"
                                + host
                                + "' was blocked: it is not in the domains this agent is allowed "
                                + "to browse ("
                                + String.join(", ", shown)
                                + "). Do not retry it or look for a way around the block - tell the user "
                                + "it was blocked.");
            }
        }
        return Decision.allow(host);
    }

    /**
     * True when {@code host} (already normalised) matches any entry of {@code patterns} (already
     * normalised).
     */
    static boolean hostMatches(List<String> patterns, String host) {
        if (host == null || host.isEmpty()) {
            return false;
        }
        for (String pattern : patterns) {
            if (pattern.equals("*")) {
                return true;
            }
            if (pattern.startsWith("*.")) {
                if (host.endsWith(pattern.substring(1))) {
                    return true;
                }
            } else if (host.equals(pattern)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Host of {@code url} for matching a secret binding against the current page: normalised host
     * or null when unusable.
     */
    static String hostOrNull(String url) {
        return parseHost(url).host();
    }

    /**
     * Normalises a list of raw entries exactly like the constructor does - used for secret-domain
     * bindings.
     */
    static List<String> normalizeEntries(List<String> raw) {
        List<String> out = new ArrayList<>();
        if (raw == null) {
            return out;
        }
        for (String entry : raw) {
            String normalized = normalizePattern(entry);
            if (normalized != null && !out.contains(normalized)) {
                out.add(normalized);
            }
        }
        return List.copyOf(out);
    }

    private record HostResult(String host, String problem) {}

    private static HostResult parseHost(String url) {
        if (url == null || url.isBlank()) {
            return new HostResult(null, "no URL was given");
        }
        URI uri;
        try {
            uri = new URI(url.strip());
        } catch (URISyntaxException e) {
            return new HostResult(null, "the URL is not a valid web address");
        }
        if (uri.getRawUserInfo() != null
                || (uri.getRawAuthority() != null && uri.getRawAuthority().indexOf('@') >= 0)) {
            return new HostResult(null, "URLs with embedded credentials are not allowed");
        }
        String host = uri.getHost();
        if (host == null) {
            // java.net.URI only fills getHost() for ASCII server authorities; an IDN (unicode) host
            // parses as a
            // "registry-based" authority, so take the authority (credentials already refused above)
            // minus port.
            String authority = uri.getRawAuthority();
            if (authority != null) {
                host = authority;
                if (!host.startsWith("[")) {
                    int colon = host.lastIndexOf(':');
                    if (colon >= 0 && host.indexOf(':') == colon) {
                        host = host.substring(0, colon);
                    }
                }
            }
        }
        if (host == null || host.isBlank()) {
            return new HostResult(null, "the URL has no usable host");
        }
        String normalized = normalizeHost(host);
        if (normalized == null) {
            return new HostResult(null, "the URL's host is not a valid domain name");
        }
        return new HostResult(normalized, null);
    }

    /**
     * Lower-case, bracket-free, trailing-dot-free, punycoded host; null when it cannot be
     * normalised.
     */
    static String normalizeHost(String raw) {
        if (raw == null) {
            return null;
        }
        String h = raw.strip().toLowerCase(Locale.ROOT);
        if (h.startsWith("[") && h.endsWith("]")) {
            h = h.substring(1, h.length() - 1);
        }
        while (h.endsWith(".")) {
            h = h.substring(0, h.length() - 1);
        }
        if (h.isEmpty()) {
            return null;
        }
        if (h.indexOf('%') >= 0
                || h.indexOf('@') >= 0
                || h.indexOf('/') >= 0
                || h.indexOf('\\') >= 0) {
            return null;
        }
        boolean ascii = h.chars().allMatch(c -> c < 0x80);
        if (!ascii) {
            try {
                // STD3 rules: refuse non-LDH characters that nameprep/NFKC could fold into "@", "/"
                // or "." lookalikes.
                h =
                        IDN.toASCII(h, IDN.ALLOW_UNASSIGNED | IDN.USE_STD3_ASCII_RULES)
                                .toLowerCase(Locale.ROOT);
            } catch (IllegalArgumentException e) {
                return null;
            }
            while (h.endsWith(".")) {
                h = h.substring(0, h.length() - 1);
            }
        }
        if (h.isEmpty() || !(LDH_HOST.matcher(h).matches() || IPV6_HOST.matcher(h).matches())) {
            return null;
        }
        return h;
    }

    private static String normalizePattern(String entry) {
        if (entry == null) {
            return null;
        }
        String e = entry.strip().toLowerCase(Locale.ROOT);
        if (e.isEmpty()) {
            return null;
        }
        if (e.equals("*")) {
            return "*";
        }
        int scheme = e.indexOf("://");
        if (scheme >= 0) {
            e = e.substring(scheme + 3);
        }
        int cut = e.length();
        for (char c : new char[] {'/', '?', '#'}) {
            int i = e.indexOf(c);
            if (i >= 0 && i < cut) {
                cut = i;
            }
        }
        e = e.substring(0, cut);
        int at = e.lastIndexOf('@');
        if (at >= 0) {
            e = e.substring(at + 1);
        }
        boolean wildcard = e.startsWith("*.");
        if (wildcard) {
            e = e.substring(2);
        }
        // Drop a :port suffix (never an IPv6 literal's colons).
        if (!e.startsWith("[")) {
            int colon = e.lastIndexOf(':');
            if (colon >= 0 && e.indexOf(':') == colon) {
                e = e.substring(0, colon);
            }
        }
        String host = normalizeHost(e);
        if (host == null) {
            log.warn("Ignoring unusable browser domain entry");
            return null;
        }
        return wildcard ? "*." + host : host;
    }

    static List<String> parseList(String raw) {
        List<String> out = new ArrayList<>();
        if (raw == null || raw.isBlank()) {
            return out;
        }
        for (String part : raw.split("[,\\s]+")) {
            if (!part.isBlank()) {
                out.add(part.strip());
            }
        }
        return out;
    }

    /**
     * {@code <agentUuid>=host,host;<agentUuid>=host} - malformed entries are skipped with a
     * warning, never fatal (same shape as {@code HttpRequestSafetyConfig}).
     */
    static Map<UUID, List<String>> parseAgentMap(String raw) {
        Map<UUID, List<String>> out = new HashMap<>();
        if (raw == null || raw.isBlank()) {
            return out;
        }
        for (String entry : raw.split(";")) {
            int eq = entry.indexOf('=');
            if (eq <= 0) {
                if (!entry.isBlank()) {
                    log.warn(
                            "Ignoring malformed app.browser per-agent domain entry (expected <agentUuid>=host,host)");
                }
                continue;
            }
            try {
                UUID agent = UUID.fromString(entry.substring(0, eq).strip());
                out.put(agent, parseList(entry.substring(eq + 1)));
            } catch (IllegalArgumentException e) {
                log.warn("Ignoring app.browser per-agent domain entry with an invalid agent id");
            }
        }
        return out;
    }

    /**
     * {@code NAME=host,host;NAME2=host} - the operator's secret-to-domain bindings; keys
     * upper-cased.
     */
    static Map<String, List<String>> parseNamedMap(String raw) {
        Map<String, List<String>> out = new HashMap<>();
        if (raw == null || raw.isBlank()) {
            return out;
        }
        for (String entry : raw.split(";")) {
            int eq = entry.indexOf('=');
            if (eq <= 0) {
                if (!entry.isBlank()) {
                    log.warn(
                            "Ignoring malformed app.browser.secret-domains entry (expected NAME=host,host)");
                }
                continue;
            }
            String name = entry.substring(0, eq).strip().toUpperCase(Locale.ROOT);
            if (!name.isEmpty()) {
                out.put(name, normalizeEntries(parseList(entry.substring(eq + 1))));
            }
        }
        return out;
    }

    private static Map<UUID, List<String>> normalizeMap(Map<UUID, List<String>> raw) {
        Map<UUID, List<String>> out = new HashMap<>();
        if (raw != null) {
            raw.forEach(
                    (agent, list) -> {
                        List<String> normalized = normalizeEntries(list);
                        if (!normalized.isEmpty()) {
                            out.put(agent, normalized);
                        }
                    });
        }
        return Map.copyOf(out);
    }
}
