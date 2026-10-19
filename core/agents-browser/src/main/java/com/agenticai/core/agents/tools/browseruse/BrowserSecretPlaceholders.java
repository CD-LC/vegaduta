package com.agenticai.core.agents.tools.browseruse;

import com.agenticai.core.secrets.SecretRedactor;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * {@code {{secret.NAME}}} placeholders for {@code browser_type_into}: the model writes the
 * placeholder, the server substitutes the vault value at the moment of typing, and the value never
 * appears in any string that travels back toward the model, a step event or a log.
 *
 * <p>This class is the pure half (parsing, the domain gate, substitution, redaction); the vault
 * lookup itself ({@code SecretReferenceResolver}, the same one {@code http_request} uses) happens
 * in {@link BrowserUseV2Settings#resolveSecrets}, on the calling tool thread because the vault
 * needs the tenant context that the Playwright executor thread does not carry.
 *
 * <h2>Domain binding</h2>
 *
 * The vault has no per-secret domain column, so the binding is operator configuration ({@code
 * app.browser.secret-domains}, {@code NAME=host,host;NAME2=host}). A secret may be typed only when
 * the page the browser is currently on matches:
 *
 * <ol>
 *   <li>the secret's own binding, when one exists (and the page must also pass the domain policy);
 *       else
 *   <li>the agent's effective allow-list - and when there is NO allow-list at all (neither operator
 *       nor agent), typing a secret is refused outright, so a prompt-injected page on "any public
 *       host" can never be the destination of a stored credential.
 * </ol>
 *
 * Spelling accepted: {@code {{secret.NAME}}} and {@code {{secret:NAME}}}, any case (the name is
 * upper-cased, matching the vault). Anything that starts like a placeholder but is malformed is
 * refused rather than typed literally.
 */
final class BrowserSecretPlaceholders {

    private BrowserSecretPlaceholders() {}

    private static final Pattern REFERENCE =
            Pattern.compile("\\{\\{\\s*secret\\s*[.:]\\s*([A-Za-z][A-Za-z0-9_]{0,63})\\s*\\}\\}");
    private static final Pattern ANY_START =
            Pattern.compile("\\{\\{\\s*secret\\s*[.:]", Pattern.CASE_INSENSITIVE);

    private static final SecretRedactor REDACTOR = new SecretRedactor();

    /** True when the text contains at least one well-formed placeholder. */
    static boolean containsPlaceholder(String text) {
        return text != null && REFERENCE.matcher(text).find();
    }

    /**
     * Distinct upper-case secret names referenced in {@code text}, in order of first appearance.
     */
    static Set<String> names(String text) {
        Set<String> names = new LinkedHashSet<>();
        if (text == null) {
            return names;
        }
        Matcher m = REFERENCE.matcher(text);
        while (m.find()) {
            names.add(m.group(1).toUpperCase(Locale.ROOT));
        }
        return names;
    }

    /**
     * Refuses text that starts a placeholder but is not a well-formed one (it would otherwise be
     * typed into the page literally).
     */
    static void requireWellFormed(String text) {
        if (text == null) {
            return;
        }
        int starts = 0;
        Matcher any = ANY_START.matcher(text);
        while (any.find()) {
            starts++;
        }
        int good = 0;
        Matcher ok = REFERENCE.matcher(text);
        while (ok.find()) {
            good++;
        }
        if (starts != good) {
            throw new BrowserUseException(
                    "a {{secret.NAME}} reference in the text is malformed - names are letters, digits "
                            + "and underscores, e.g. {{secret.PORTAL_PASSWORD}}");
        }
    }

    /**
     * Throws a {@link BrowserUseException} unless every named secret may be typed on the page at
     * {@code pageHost}. Messages name the secret (never its value) and the rule that refused it.
     *
     * @param pageHost normalised host of the page the browser is on right now (null/blank = unknown
     *     = refuse)
     * @param bindings operator secret-to-domain bindings, keys upper-case, values already
     *     normalised
     */
    static void authorize(
            Set<String> names,
            String pageHost,
            Map<String, List<String>> bindings,
            BrowserDomainPolicy policy,
            UUID agentId) {
        if (names.isEmpty()) {
            return;
        }
        if (pageHost == null || pageHost.isBlank()) {
            throw new BrowserUseException(
                    "a vault secret cannot be typed here: the current page's domain could not be determined");
        }
        for (String name : names) {
            List<String> bound = bindings == null ? null : bindings.get(name);
            if (bound != null && !bound.isEmpty()) {
                if (!BrowserDomainPolicy.hostMatches(bound, pageHost)) {
                    throw new BrowserUseException(
                            "secret "
                                    + name
                                    + " is bound to specific domains and may not be typed on '"
                                    + pageHost
                                    + "' - do not try to work around this; tell the user");
                }
            } else if (!policy.hasAllowList(agentId)) {
                throw new BrowserUseException(
                        "secret "
                                + name
                                + " cannot be typed into a web page because this agent has no "
                                + "allowed-domains list and the secret is not bound to a domain - an administrator must configure "
                                + "app.browser.allowed-domains (or agent-allowed-domains, or secret-domains) first. Do not ask the user for "
                                + "the value; tell them what is missing");
            }
            BrowserDomainPolicy.Decision decision =
                    policy.check(agentId, "https://" + pageHost + "/");
            if (!decision.allowed()) {
                throw new BrowserUseException(
                        "secret "
                                + name
                                + " may not be typed on '"
                                + pageHost
                                + "': that domain is not permitted for this agent");
            }
        }
    }

    /**
     * Replaces every well-formed placeholder in {@code text} with {@code values.get(NAME)}; the
     * caller guarantees every name has a value.
     */
    static String substitute(String text, Map<String, String> values) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        Matcher m = REFERENCE.matcher(text);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            String value = values.get(m.group(1).toUpperCase(Locale.ROOT));
            if (value == null) {
                throw new BrowserUseException(
                        "secret " + m.group(1).toUpperCase(Locale.ROOT) + " could not be resolved");
            }
            m.appendReplacement(out, Matcher.quoteReplacement(value));
        }
        m.appendTail(out);
        return out.toString();
    }

    /**
     * The shared redactor ignores values shorter than this; this class scrubs those itself (exact
     * match only).
     */
    private static final int SHARED_REDACTOR_MIN_LENGTH = 6;

    /**
     * A vault value shorter than this is refused for typing: scrubbing 1-3 characters would mangle
     * every page text.
     */
    static final int MIN_TYPEABLE_LENGTH = 4;

    private static final String SHORT_MASK = "[redacted:secret]";

    /**
     * Scrubs every supplied secret value (and its URL-encoded / base64 / JSON-escaped forms) out of
     * {@code text}. Values under 6 characters (a short PIN or password) are skipped by the shared
     * redactor, so for the browser path they get a plain exact-match replace here - a page can echo
     * what was typed into it.
     */
    static String redact(String text, Collection<String> secretValues) {
        String result = REDACTOR.redact(text, secretValues);
        if (result == null || result.isEmpty() || secretValues == null) {
            return result;
        }
        for (String value : secretValues) {
            if (isShort(value)) {
                result = result.replace(value, SHORT_MASK);
            }
        }
        return result;
    }

    /**
     * True when any supplied value (in any scrubbed form) is still present - for tests and
     * defensive assertions.
     */
    static boolean leaks(String text, Collection<String> secretValues) {
        if (REDACTOR.containsAny(text, secretValues)) {
            return true;
        }
        if (text != null && secretValues != null) {
            for (String value : secretValues) {
                if (isShort(value) && text.contains(value)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Fail-closed guard for {@code browser_type_into}: refuses a resolved vault value too short to
     * be scrubbed from later page text without mangling it. The message names the secret, never its
     * value.
     */
    static void requireTypeable(Map<String, String> resolvedValues) {
        if (resolvedValues == null) {
            return;
        }
        for (Map.Entry<String, String> entry : resolvedValues.entrySet()) {
            String value = entry.getValue();
            if (value != null && value.length() < MIN_TYPEABLE_LENGTH) {
                throw new BrowserUseException(
                        "secret "
                                + entry.getKey()
                                + " is too short (under "
                                + MIN_TYPEABLE_LENGTH
                                + " characters) to be typed into a web page safely, because it could not be hidden from later page text - "
                                + "tell the user to use a longer secret; do not ask for its value");
            }
        }
    }

    private static boolean isShort(String value) {
        return value != null && !value.isEmpty() && value.length() < SHARED_REDACTOR_MIN_LENGTH;
    }
}
