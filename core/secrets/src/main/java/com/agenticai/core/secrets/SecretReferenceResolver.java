package com.agenticai.core.secrets;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * Expands {@code {{secret.NAME}}} references into real values.
 *
 * <h2>Why a reference instead of the value</h2>
 * An agent that needs a password has to get it from somewhere. The tempting
 * shortcut - put it in the system prompt, or in the tool's config JSON - loses
 * immediately, because in this platform both of those are sent to the model
 * provider on every single turn, land in Langfuse traces, and are replayed
 * into session memory. The secret would leak to a third party before the tool
 * ever ran.
 *
 * <p>So the value is never in the config. The config holds the <i>name</i>.
 * The model sees, reasons about, and may even repeat {@code {{secret.STRIPE_KEY}}},
 * and that string is worth nothing. This class runs strictly <b>after</b> the
 * model has produced a tool call and strictly <b>inside</b> the tool-execution
 * boundary, where the resolved value goes directly into the carrier - an env
 * var for an MCP stdio process, an HTTP header for a remote server - and
 * nowhere else.
 *
 * <h2>The return contract exists for redaction</h2>
 * {@link Resolution} deliberately hands back the set of values it substituted.
 * Callers are expected to feed that set to {@link SecretRedactor} before any
 * tool output, error message or stack trace travels back toward the model.
 * Resolving without redacting re-opens the leak from the other side: a tool
 * that echoes its own configuration, or a connection error that quotes the
 * failing header, would put the plaintext straight into the transcript.
 */
@Component
public class SecretReferenceResolver {

    /**
     * Matches the reference form and nothing else. The name group mirrors
     * {@code SecretVaultService.VALID_NAME}, so a reference that would name an
     * un-storable secret simply does not match and is left as literal text
     * rather than becoming an unresolved-reference error.
     *
     * <p>Optional inner whitespace is tolerated because these strings are
     * hand-typed into web forms.
     */
    private static final Pattern REFERENCE =
        Pattern.compile("\\{\\{\\s*secret\\.([A-Z][A-Z0-9_]{0,63})\\s*\\}\\}");

    private final SecretVaultService vaultService;

    public SecretReferenceResolver(SecretVaultService vaultService) {
        this.vaultService = vaultService;
    }

    /**
     * The outcome of expanding one template or map.
     *
     * @param resolvedValues every plaintext value substituted, for
     *        {@link SecretRedactor}. Never logged, never serialized.
     * @param unresolvedNames references that named a secret this user cannot
     *        resolve. Reported to the caller so it can fail the tool call with
     *        an actionable "add STRIPE_KEY to your vault" message instead of
     *        silently calling the upstream API with a literal
     *        {@code {{secret.STRIPE_KEY}}} as the key - which would look like
     *        an auth failure and waste everyone's afternoon.
     */
    public record Resolution(String value, Set<String> resolvedValues, List<String> unresolvedNames) {

        public Resolution {
            resolvedValues = Collections.unmodifiableSet(new LinkedHashSet<>(resolvedValues));
            unresolvedNames = List.copyOf(unresolvedNames);
        }

        public boolean hasUnresolved() {
            return !unresolvedNames.isEmpty();
        }
    }

    /** True when the text contains at least one reference - a cheap pre-check. */
    public boolean containsReference(String text) {
        return text != null && REFERENCE.matcher(text).find();
    }

    /**
     * Expands every reference in {@code template} for a run initiated by
     * {@code userId}.
     *
     * <p>Each distinct name is resolved once even if referenced repeatedly, so
     * a config mentioning the same secret in three places produces one audit
     * row rather than three - the audit log should record intent, not loop
     * iterations.
     */
    public Resolution resolve(String template, String userId) {
        return resolveWith(template, n -> vaultService.resolveForRun(n, userId));
    }

    /**
     * URL-bound variant (SEC-006): each reference resolves only when the
     * secret's allowed hosts match {@code destinationHost}; see
     * {@link SecretVaultService#resolveForDestination}. A reference that is
     * missing, revoked, or not allowed for this destination lands in
     * {@link Resolution#unresolvedNames()} and the caller must fail the
     * request with ONE generic message for all of those cases.
     */
    public Resolution resolveForDestination(String template, String userId, String destinationHost) {
        return resolveWith(template, n -> vaultService.resolveForDestination(n, userId, destinationHost));
    }

    private Resolution resolveWith(String template, java.util.function.Function<String, Optional<String>> lookup) {
        if (template == null || template.isEmpty()) {
            return new Resolution(template, Set.of(), List.of());
        }
        Matcher matcher = REFERENCE.matcher(template);
        if (!matcher.find()) {
            return new Resolution(template, Set.of(), List.of());
        }

        Map<String, Optional<String>> cache = new LinkedHashMap<>();
        Set<String> resolvedValues = new LinkedHashSet<>();
        List<String> unresolved = new ArrayList<>();
        StringBuilder out = new StringBuilder();

        matcher.reset();
        while (matcher.find()) {
            String name = matcher.group(1);
            Optional<String> value = cache.computeIfAbsent(name, lookup);
            if (value.isPresent()) {
                resolvedValues.add(value.get());
                // quoteReplacement: a secret containing '$' or '\' would
                // otherwise be interpreted as a group reference and either
                // corrupt the value or throw mid-substitution.
                matcher.appendReplacement(out, Matcher.quoteReplacement(value.get()));
            } else {
                if (!unresolved.contains(name)) {
                    unresolved.add(name);
                }
                // Leave the reference text in place. Blanking it would turn a
                // missing secret into an empty password, and some upstreams
                // treat an empty credential as anonymous access rather than
                // as an error.
                matcher.appendReplacement(out, Matcher.quoteReplacement(matcher.group(0)));
            }
        }
        matcher.appendTail(out);
        return new Resolution(out.toString(), resolvedValues, unresolved);
    }

    /**
     * Map variant, for the shapes secrets actually get injected through: an
     * MCP server's env map, or a set of HTTP headers.
     *
     * <p>Keys are never expanded - only values. A reference in a key would
     * mean the secret's <i>value</i> becomes an env-var or header <i>name</i>,
     * which is never what someone meant and would put plaintext into a process
     * listing.
     */
    public Resolution resolveAll(Map<String, String> source, String userId, Map<String, String> target) {
        Set<String> resolvedValues = new LinkedHashSet<>();
        List<String> unresolved = new ArrayList<>();
        for (Map.Entry<String, String> entry : source.entrySet()) {
            Resolution one = resolve(entry.getValue(), userId);
            target.put(entry.getKey(), one.value());
            resolvedValues.addAll(one.resolvedValues());
            for (String name : one.unresolvedNames()) {
                if (!unresolved.contains(name)) {
                    unresolved.add(name);
                }
            }
        }
        return new Resolution(null, resolvedValues, unresolved);
    }
}
