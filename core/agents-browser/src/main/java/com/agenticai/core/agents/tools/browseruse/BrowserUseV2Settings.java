package com.agenticai.core.agents.tools.browseruse;

import com.agenticai.core.secrets.SecretReferenceResolver;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Configuration and collaborators of browser agent v2 (indexed snapshot tools, domain allow/deny,
 * vault secret placeholders, stuck detection), all under {@code app.browser.*}.
 *
 * <p>{@code app.browser.v2.enabled} defaults to <b>false</b>: with it off no v2 tool is registered
 * and the five existing {@code browser_*} tools behave exactly as before. The domain lists ({@code
 * app.browser.allowed-domains} etc.) are independent of that flag but empty by default, which is
 * today's behaviour (any public host that passes the SSRF guard).
 *
 * <p>Single constructor on purpose (see {@code InjectableConstructorTest}); every {@code @Value}
 * repeats its default so a slim test context constructs. The vault resolver is optional ({@link
 * ObjectProvider}, may be null in tests): without it a secret placeholder fails closed with a clear
 * message.
 */
@Component
public class BrowserUseV2Settings {

    private final boolean enabled;
    private final int maxElements;
    private final int nameMaxChars;
    private final int valueMaxChars;
    private final int digestChars;
    private final boolean strictStaleness;
    private final int stuckWarnAfter;
    private final int stuckAbortAfter;
    private final boolean legacyClickTypeEnabled;
    private final BrowserDomainPolicy domainPolicy;
    private final Map<String, List<String>> secretBindings;
    private final ObjectProvider<SecretReferenceResolver> secretResolver;

    public BrowserUseV2Settings(
            @Value("${app.browser.v2.enabled:false}") boolean enabled,
            @Value("${app.browser.v2.max-elements:120}") int maxElements,
            @Value("${app.browser.v2.name-max-chars:80}") int nameMaxChars,
            @Value("${app.browser.v2.value-max-chars:40}") int valueMaxChars,
            @Value("${app.browser.v2.text-digest-chars:400}") int digestChars,
            @Value("${app.browser.v2.strict-staleness:false}") boolean strictStaleness,
            @Value("${app.browser.v2.stuck-warn-after:4}") int stuckWarnAfter,
            @Value("${app.browser.v2.stuck-abort-after:8}") int stuckAbortAfter,
            @Value("${app.browser.v2.legacy-click-type-enabled:false}")
                    boolean legacyClickTypeEnabled,
            @Value("${app.browser.allowed-domains:}") String allowedDomains,
            @Value("${app.browser.blocked-domains:}") String blockedDomains,
            @Value("${app.browser.agent-allowed-domains:}") String agentAllowedDomains,
            @Value("${app.browser.agent-blocked-domains:}") String agentBlockedDomains,
            @Value("${app.browser.secret-domains:}") String secretDomains,
            ObjectProvider<SecretReferenceResolver> secretResolver) {
        this.enabled = enabled;
        this.maxElements = clamp(maxElements, 10, 400);
        this.nameMaxChars = clamp(nameMaxChars, 20, 200);
        this.valueMaxChars = clamp(valueMaxChars, 10, 200);
        this.digestChars = clamp(digestChars, 0, 2_000);
        this.strictStaleness = strictStaleness;
        this.stuckWarnAfter = stuckWarnAfter;
        this.stuckAbortAfter = stuckAbortAfter;
        this.legacyClickTypeEnabled = legacyClickTypeEnabled;
        this.domainPolicy =
                BrowserDomainPolicy.fromConfig(
                        allowedDomains, blockedDomains, agentAllowedDomains, agentBlockedDomains);
        this.secretBindings = BrowserDomainPolicy.parseNamedMap(secretDomains);
        this.secretResolver = secretResolver;
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    /** Whether the v2 tools are offered. Default false. */
    public boolean isEnabled() {
        return enabled;
    }

    /**
     * Whether the legacy {@code browser_click}/{@code browser_type} stay on the tool list when v2
     * is on. Default false (they bypass the irreversible-click gate).
     */
    public boolean isLegacyClickTypeEnabled() {
        return legacyClickTypeEnabled;
    }

    int maxElements() {
        return maxElements;
    }

    int nameMaxChars() {
        return nameMaxChars;
    }

    int valueMaxChars() {
        return valueMaxChars;
    }

    int digestChars() {
        return digestChars;
    }

    boolean strictStaleness() {
        return strictStaleness;
    }

    BrowserDomainPolicy domainPolicy() {
        return domainPolicy;
    }

    Map<String, List<String>> secretBindings() {
        return secretBindings;
    }

    BrowserV2State newState() {
        return new BrowserV2State(stuckWarnAfter, stuckAbortAfter);
    }

    /**
     * Resolves vault secrets by name for one run initiated by {@code userId}, on the CALLING (tool)
     * thread - the vault needs the tenant context, which the Playwright executor thread does not
     * carry. Returns {@code NAME -> plaintext}. The values must go only to {@code Locator.fill} and
     * to the session's redaction set; they are never logged and never put in a result.
     *
     * @throws BrowserUseException when no vault is available or a name does not resolve (the
     *     message names the secret, never a value)
     */
    Map<String, String> resolveSecrets(Set<String> names, String userId) {
        Map<String, String> values = new LinkedHashMap<>();
        if (names.isEmpty()) {
            return values;
        }
        SecretReferenceResolver resolver =
                secretResolver == null ? null : secretResolver.getIfAvailable();
        if (resolver == null) {
            throw new BrowserUseException("vault secrets are not available in this deployment");
        }
        for (String name : names) {
            SecretReferenceResolver.Resolution resolution =
                    resolver.resolve("{{secret." + name + "}}", userId);
            if (resolution.hasUnresolved()
                    || resolution.value() == null
                    || resolution.value().isEmpty()) {
                throw new BrowserUseException(
                        "secret "
                                + name
                                + " could not be resolved - ask the user to add it on the Secrets "
                                + "page (as a workspace secret, or their own)");
            }
            values.put(name, resolution.value());
        }
        return values;
    }
}
