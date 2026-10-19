package com.agenticai.core.agents.tools.browseruse;

import com.agenticai.core.json.JsonMappers;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.net.URI;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Pure helpers for a browser profile's Playwright {@code storageState} JSON ({@code
 * {"cookies":[...],"origins":[{"origin":"https://x","localStorage":[...]}]}}) and its domain scope
 * - no Spring, no Playwright, so the scoping rules are unit-testable on their own.
 *
 * <p>A profile is domain-scoped: it records the hosts it may be used on when it is created, and the
 * saved state is filtered to those hosts on BOTH save and load, so a login captured while an agent
 * wandered to another site is never stored, and a stale or tampered row can never seed a context
 * with cookies for a domain the profile was not created for.
 */
final class BrowserStorageState {

    private static final ObjectMapper MAPPER = JsonMappers.plain();

    /** Most domains one profile may allow (see {@link #normalizeDomains}). */
    static final int MAX_ALLOWED_DOMAINS = 20;

    /**
     * At least two DNS labels, letters/digits/hyphens: a registrable-looking host, never an IP
     * literal or a bare TLD.
     */
    private static final Pattern DOMAIN =
            Pattern.compile(
                    "[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?(\\.[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?)+");

    private static final Pattern ALL_DIGITS_AND_DOTS = Pattern.compile("[0-9.]+");

    private BrowserStorageState() {}

    /**
     * Normalizes one user-entered domain: trims, lower-cases, accepts a pasted URL ({@code
     * https://app.example.com/login} becomes {@code app.example.com}), drops a leading {@code *.}
     * or {@code .} and any port.
     *
     * @throws IllegalArgumentException when the result is not a plausible public hostname
     */
    static String normalizeDomain(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("a domain must not be blank");
        }
        String value = raw.strip().toLowerCase(Locale.ROOT);
        if (value.contains("://")) {
            try {
                String host = URI.create(value).getHost();
                if (host == null) {
                    throw new IllegalArgumentException(
                            "'" + raw.strip() + "' is not a valid domain");
                }
                value = host;
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("'" + raw.strip() + "' is not a valid domain");
            }
        }
        int slash = value.indexOf('/');
        if (slash >= 0) {
            value = value.substring(0, slash);
        }
        int colon = value.lastIndexOf(':');
        if (colon >= 0) {
            value = value.substring(0, colon);
        }
        if (value.startsWith("*.")) {
            value = value.substring(2);
        }
        while (value.startsWith(".")) {
            value = value.substring(1);
        }
        while (value.endsWith(".")) {
            value = value.substring(0, value.length() - 1);
        }
        if (value.length() > 253
                || ALL_DIGITS_AND_DOTS.matcher(value).matches()
                || !DOMAIN.matcher(value).matches()) {
            throw new IllegalArgumentException(
                    "'"
                            + raw.strip()
                            + "' is not a valid domain (use a hostname such as app.example.com)");
        }
        return value;
    }

    /**
     * {@link #normalizeDomain} over a list, de-duplicated, order preserved. At least one is
     * required.
     */
    static List<String> normalizeDomains(Collection<String> raw) {
        if (raw == null || raw.isEmpty()) {
            throw new IllegalArgumentException("at least one allowed domain is required");
        }
        // Bounded: every save and load filters the cookies against the whole list (cookies x
        // domains) and the
        // list lives in one TEXT column. Checked on the raw size first so a huge payload is never
        // normalised.
        if (raw.size() > MAX_ALLOWED_DOMAINS * 4) {
            throw new IllegalArgumentException(
                    "a profile can allow at most " + MAX_ALLOWED_DOMAINS + " domains");
        }
        Set<String> out = new LinkedHashSet<>();
        for (String domain : raw) {
            out.add(normalizeDomain(domain));
        }
        if (out.size() > MAX_ALLOWED_DOMAINS) {
            throw new IllegalArgumentException(
                    "a profile can allow at most " + MAX_ALLOWED_DOMAINS + " domains");
        }
        return List.copyOf(out);
    }

    /**
     * True when {@code host} is one of {@code allowedDomains} or a subdomain of one. Both sides are
     * compared lower-case.
     */
    static boolean hostAllowed(String host, Collection<String> allowedDomains) {
        if (host == null || host.isBlank() || allowedDomains == null) {
            return false;
        }
        String h = host.strip().toLowerCase(Locale.ROOT);
        for (String allowed : allowedDomains) {
            if (allowed == null || allowed.isBlank()) {
                continue;
            }
            String d = allowed.strip().toLowerCase(Locale.ROOT);
            if (h.equals(d) || h.endsWith("." + d)) {
                return true;
            }
        }
        return false;
    }

    /**
     * The host of an http(s) URL, lower-cased, or {@code null} for anything else (about:, data:,
     * unparsable).
     */
    static String httpHostOf(String url) {
        if (url == null) {
            return null;
        }
        try {
            URI uri = URI.create(url.strip());
            String scheme = uri.getScheme();
            if (scheme == null
                    || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) {
                return null;
            }
            String host = uri.getHost();
            return host == null ? null : host.toLowerCase(Locale.ROOT);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /**
     * A cookie is kept when its domain falls inside the profile's scope, or is a PARENT of a scoped
     * host (a login cookie set on {@code .example.com} for {@code app.example.com} is the usual
     * shape; dropping it would lose the session). Navigation outside the scope is refused
     * separately by the session guard.
     */
    static boolean cookieDomainInScope(String cookieDomain, Collection<String> allowedDomains) {
        if (cookieDomain == null || cookieDomain.isBlank() || allowedDomains == null) {
            return false;
        }
        String cd = cookieDomain.strip().toLowerCase(Locale.ROOT);
        while (cd.startsWith(".")) {
            cd = cd.substring(1);
        }
        if (cd.isEmpty()) {
            return false;
        }
        if (hostAllowed(cd, allowedDomains)) {
            return true;
        }
        for (String allowed : allowedDomains) {
            if (allowed != null && allowed.strip().toLowerCase(Locale.ROOT).endsWith("." + cd)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Returns {@code storageStateJson} reduced to the cookies and localStorage origins inside
     * {@code allowedDomains}. Unknown top-level fields are dropped (only {@code cookies} and {@code
     * origins} survive).
     *
     * @throws IllegalArgumentException when the input is not a JSON object
     */
    static String filterToDomains(String storageStateJson, Collection<String> allowedDomains) {
        JsonNode root;
        try {
            root = MAPPER.readTree(storageStateJson);
        } catch (Exception e) {
            throw new IllegalArgumentException("the browser state is not valid JSON");
        }
        if (root == null || !root.isObject()) {
            throw new IllegalArgumentException("the browser state is not a JSON object");
        }
        ObjectNode out = MAPPER.createObjectNode();
        ArrayNode cookies = out.putArray("cookies");
        JsonNode inCookies = root.get("cookies");
        if (inCookies != null && inCookies.isArray()) {
            for (JsonNode cookie : inCookies) {
                JsonNode domain = cookie.get("domain");
                if (domain != null
                        && domain.isTextual()
                        && cookieDomainInScope(domain.asText(), allowedDomains)) {
                    cookies.add(cookie);
                }
            }
        }
        ArrayNode origins = out.putArray("origins");
        JsonNode inOrigins = root.get("origins");
        if (inOrigins != null && inOrigins.isArray()) {
            for (JsonNode origin : inOrigins) {
                JsonNode originUrl = origin.get("origin");
                if (originUrl != null
                        && originUrl.isTextual()
                        && hostAllowed(httpHostOf(originUrl.asText()), allowedDomains)) {
                    origins.add(origin);
                }
            }
        }
        return out.toString();
    }

    /**
     * Counts cookies + localStorage entries in a state document, for the (content-free) profile
     * summary.
     */
    static int entryCount(String storageStateJson) {
        try {
            JsonNode root = MAPPER.readTree(storageStateJson);
            int count = 0;
            JsonNode cookies = root.get("cookies");
            if (cookies != null && cookies.isArray()) {
                count += cookies.size();
            }
            JsonNode origins = root.get("origins");
            if (origins != null && origins.isArray()) {
                for (JsonNode origin : origins) {
                    JsonNode ls = origin.get("localStorage");
                    if (ls != null && ls.isArray()) {
                        count += ls.size();
                    }
                }
            }
            return count;
        } catch (Exception e) {
            return 0;
        }
    }

    /** Joins domains for the single text column that stores them. */
    static String joinDomains(List<String> domains) {
        return String.join(",", domains);
    }

    /** Inverse of {@link #joinDomains}; tolerant of blanks. */
    static List<String> splitDomains(String stored) {
        List<String> out = new ArrayList<>();
        if (stored == null) {
            return out;
        }
        for (String part : stored.split(",")) {
            if (!part.isBlank()) {
                out.add(part.strip().toLowerCase(Locale.ROOT));
            }
        }
        return out;
    }
}
