package com.agenticai.core.agents.tools.web;

import com.agenticai.core.secrets.SecretReferenceResolver;
import com.agenticai.core.security.TenantContext;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.beans.factory.ObjectProvider;

/**
 * ResolveSecretsStage of the {@link HttpRequestExecutor} pipeline: turns {@code {{secret.NAME}}}
 * references into plaintext, strictly bound to the destination host (SEC-006), and renders workflow
 * input placeholders in ONE pass over the author's text (SEC-007). Missing, revoked and
 * not-allowed references all fail with ONE generic message so secret names cannot be enumerated.
 */
final class HttpSecretResolution {

    /**
     * The one message for every missing / revoked / not-allowed secret reference (no name
     * enumeration).
     */
    static final String SECRET_NOT_AVAILABLE =
            "a referenced secret is not available for this destination";

    /** Any {@code {{...}}} placeholder without nested braces - the innermost one wins. */
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{([^{}]*)\\}\\}");

    /**
     * Inner text of a vault reference: dot form (upper-case names) or colon form (any case,
     * normalised).
     */
    private static final Pattern SECRET_DOT =
            Pattern.compile("\\s*secret\\.([A-Z][A-Z0-9_]{0,63})\\s*");

    private static final Pattern SECRET_COLON =
            Pattern.compile("\\s*secret\\s*:\\s*([A-Za-z][A-Za-z0-9_]{0,63})\\s*");

    /**
     * Any spelling of a vault reference ({@code {{secret.X}}}, {@code {{secret:x}}}), any case -
     * refused in safe mode.
     */
    private static final Pattern ANY_SECRET_REFERENCE =
            Pattern.compile("\\{\\{\\s*secret\\s*[.:]", Pattern.CASE_INSENSITIVE);

    private final ObjectProvider<SecretReferenceResolver> resolver;

    HttpSecretResolution(ObjectProvider<SecretReferenceResolver> resolver) {
        this.resolver = resolver;
    }

    /** A sink bound to one request's destination host and subject. */
    SecretSink sink(String vaultSubject, String destinationHost, Set<String> resolvedValues) {
        return new SecretSink(
                resolver.getIfAvailable(), vaultSubject, destinationHost, resolvedValues);
    }

    /**
     * The named secret's value for this user, with it (and nothing else) added to the redaction
     * set. The secret resolves only when its per-secret allowed hosts match {@code
     * destinationHost} (SEC-006), and a synthetic/untrusted caller resolves TENANT-scoped secrets
     * only.
     */
    String resolveCredentialSecret(
            String name, String userId, String destinationHost, Set<String> resolvedValues) {
        SecretReferenceResolver resolver = this.resolver.getIfAvailable();
        if (resolver == null) {
            throw new WebPageFetchException("vault secrets are not available in this deployment");
        }
        TenantContext ctx = TenantContext.current().orElse(null);
        boolean untrusted = HttpRequestValidation.untrustedCaller(userId, ctx);
        String vaultSubject = untrusted ? null : userId;
        SecretReferenceResolver.Resolution resolution =
                resolver.resolveForDestination(
                        "{{secret." + name.toUpperCase(Locale.ROOT) + "}}",
                        vaultSubject,
                        destinationHost);
        if (resolution.hasUnresolved()
                || resolution.value() == null
                || resolution.value().isBlank()) {
            // Never echo the supplied name: a model can paste a raw API key (alphanumeric, so it
            // passes the name pattern)
            // and this message reaches the model, the step events and the tool-call log.
            throw new WebPageFetchException(
                    "the named credential could not be found - add it on the Secrets "
                            + "page (as a workspace secret, or your own)");
        }
        resolvedValues.addAll(resolution.resolvedValues());
        return resolution.value();
    }

    /** Resolves vault references for one request, bound to its destination host. */
    static final class SecretSink {
        private final SecretReferenceResolver resolver;
        private final String userId;
        private final String destinationHost;
        private final Set<String> resolvedValues;
        private final Map<String, String> cache = new LinkedHashMap<>();
        boolean failed;

        SecretSink(
                SecretReferenceResolver resolver,
                String userId,
                String destinationHost,
                Set<String> resolvedValues) {
            this.resolver = resolver;
            this.userId = userId;
            this.destinationHost = destinationHost;
            this.resolvedValues = resolvedValues;
        }

        /**
         * Plaintext for {@code name}, or null (and {@link #failed} set) when not available here.
         */
        String resolve(String name) {
            if (cache.containsKey(name)) {
                return cache.get(name);
            }
            if (resolver == null) {
                // No vault in this context: fail closed - never send the
                // reference text upstream as a credential.
                throw new WebPageFetchException(
                        "vault secrets are not available in this deployment");
            }
            SecretReferenceResolver.Resolution resolution =
                    resolver.resolveForDestination(
                            "{{secret." + name + "}}", userId, destinationHost);
            String value = null;
            if (resolution.unresolvedNames().isEmpty() && resolution.resolvedValues().size() == 1) {
                value = resolution.value();
                resolvedValues.addAll(resolution.resolvedValues());
            } else {
                failed = true;
            }
            cache.put(name, value);
            return value;
        }
    }

    /**
     * Renders {@code template} in ONE pass (SEC-007). Each {@code {{...}}} in the AUTHOR's text is
     * classified from that text alone: a vault reference resolves through {@code sink} (left
     * literal when {@code sink} is null), a key of {@code values} is replaced by its value VERBATIM
     * - the substituted text is never scanned again, so it cannot form or complete a vault
     * reference - and anything else stays literal.
     */
    static String render(String template, Map<String, String> values, SecretSink sink) {
        if (template == null || template.isEmpty() || template.indexOf("{{") < 0) {
            return template;
        }
        Matcher matcher = PLACEHOLDER.matcher(template);
        StringBuilder out = new StringBuilder();
        int last = 0;
        while (matcher.find()) {
            out.append(template, last, matcher.start());
            last = matcher.end();
            String inner = matcher.group(1);
            String secretName = secretName(inner);
            if (secretName != null) {
                String value = sink == null ? null : sink.resolve(secretName);
                out.append(value != null ? value : matcher.group(0));
            } else if (values != null && values.containsKey(inner)) {
                String value = values.get(inner);
                out.append(value == null ? "" : value);
            } else {
                out.append(matcher.group(0));
            }
        }
        out.append(template, last, template.length());
        return out.toString();
    }

    /** The upper-cased secret name when {@code inner} is a vault reference, else null. */
    private static String secretName(String inner) {
        Matcher dot = SECRET_DOT.matcher(inner);
        if (dot.matches()) {
            return dot.group(1);
        }
        Matcher colon = SECRET_COLON.matcher(inner);
        if (colon.matches()) {
            return colon.group(1).toUpperCase(Locale.ROOT);
        }
        return null;
    }

    /**
     * {@code {{secret:name}}} -> {@code {{secret.NAME}}}; package-private test seam kept for
     * compatibility.
     */
    static String normaliseColonReferences(String text) {
        if (text == null || text.indexOf("{{") < 0) {
            return text;
        }
        Matcher matcher = PLACEHOLDER.matcher(text);
        StringBuilder out = new StringBuilder();
        while (matcher.find()) {
            Matcher colon = SECRET_COLON.matcher(matcher.group(1));
            String replacement =
                    colon.matches()
                            ? "{{secret." + colon.group(1).toUpperCase(Locale.ROOT) + "}}"
                            : matcher.group(0);
            matcher.appendReplacement(out, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(out);
        return out.toString();
    }

    static boolean hasSecretReference(String text) {
        return text != null && ANY_SECRET_REFERENCE.matcher(text).find();
    }

    static boolean anyValueHasSecretReference(Map<String, String> values) {
        if (values == null) {
            return false;
        }
        for (String value : values.values()) {
            if (hasSecretReference(value)) {
                return true;
            }
        }
        return false;
    }
}
