package com.agenticai.core.agents.tools.browseruse;

import com.agenticai.core.secrets.SecretVaultService;
import com.agenticai.core.security.TenantContext;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.LongSupplier;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

/**
 * The {@code browser_totp} built-in: returns the CURRENT two-factor code (RFC 6238) for a TOTP seed
 * held in the tenant's secrets vault, so a browser agent can complete a 2FA prompt on a site it is
 * logged into.
 *
 * <p>The seed is resolved server-side through {@link SecretVaultService#resolveForRun} (audited as
 * a SECRET_ACCESSED row, like every other vault read), turned into a code here, and dropped. Only
 * the short-lived code (valid for the rest of its 30-second step) goes back to the model - never
 * the seed, and no error message contains it. Identity is never a tool argument: the vault read
 * runs as the {@link TenantContext} subject.
 *
 * <p>Classified as a tenant-scoped credential read that is gated by human confirmation by default
 * (see BuiltInToolClassifications); behind {@code app.browser.totp.enabled} (default off).
 */
public class BrowserTotpToolCallback implements ToolCallback {

    public static final String NAME = "browser_totp";

    private static final Logger log = LoggerFactory.getLogger(BrowserTotpToolCallback.class);

    /**
     * Mirrors SecretVaultService.VALID_NAME (after upper-casing); a name outside it cannot exist in
     * the vault.
     */
    private static final Pattern VALID_NAME = Pattern.compile("[A-Z][A-Z0-9_]{0,63}");

    private final SecretVaultService vault;
    private final LongSupplier unixSeconds;
    private final String secretNamePrefix;

    /**
     * @param secretNamePrefix only vault secrets whose name starts with this may be used as a TOTP
     *     seed (empty = any). Keeps a prompt-injected agent from asking for a code derived from an
     *     unrelated secret.
     */
    public BrowserTotpToolCallback(SecretVaultService vault, String secretNamePrefix) {
        this(vault, () -> System.currentTimeMillis() / 1000L, secretNamePrefix);
    }

    /** Test seam: a fixed clock. */
    BrowserTotpToolCallback(
            SecretVaultService vault, LongSupplier unixSeconds, String secretNamePrefix) {
        this.vault = vault;
        this.unixSeconds = unixSeconds;
        this.secretNamePrefix =
                secretNamePrefix == null ? "" : secretNamePrefix.strip().toUpperCase(Locale.ROOT);
    }

    @Override
    public ToolDefinition getToolDefinition() {
        return ToolDefinition.builder()
                .name(NAME)
                .description(
                        "Returns the current 6-digit two-factor (TOTP) code for a vault secret that holds the account's "
                                + "authenticator seed, valid for the seconds reported. Use it only when a site you are logged "
                                + "into asks for a verification code; type the code with browser_type right away. The seed "
                                + "itself is never shown.")
                .inputSchema(
                        """
                {
                    "type": "object",
                    "properties": {
                        "secretName": {
                            "type": "string",
                            "description": "Name of the vault secret holding the TOTP seed, e.g. BROWSER_GITHUB_TOTP."
                        }
                    },
                    "required": ["secretName"]
                }
                """)
                .build();
    }

    @Override
    public String call(String toolInput) {
        Optional<String> tenantId = BrowserUseToolSupport.resolveTenantId();
        if (tenantId.isEmpty()) {
            return BrowserUseToolSupport.noTenantContextError();
        }
        Map<String, Object> args = BrowserUseToolSupport.parseArgs(toolInput);
        String requested = BrowserUseToolSupport.asTrimmedString(args.get("secretName"));
        if (requested == null) {
            return BrowserUseToolSupport.errorJson("The 'secretName' argument is required.");
        }
        String name = requested.toUpperCase(Locale.ROOT);
        if (!VALID_NAME.matcher(name).matches()) {
            return BrowserUseToolSupport.errorJson(
                    "'"
                            + requested
                            + "' is not a valid vault secret name (letters, digits and underscores, starting with a letter).");
        }
        if (!secretNamePrefix.isEmpty() && !name.startsWith(secretNamePrefix)) {
            return BrowserUseToolSupport.errorJson(
                    "Only vault secrets whose name starts with "
                            + secretNamePrefix
                            + " can be used as a browser TOTP seed.");
        }
        String subject = TenantContext.current().map(TenantContext::getSubject).orElse(null);
        try {
            Optional<String> seed = vault.resolveForRun(name, subject);
            if (seed.isEmpty()) {
                return BrowserUseToolSupport.errorJson(
                        "No usable vault secret named "
                                + name
                                + " (missing, revoked, expired, or not visible to this user).");
            }
            BrowserTotp.Config config = BrowserTotp.parseSecret(seed.get());
            BrowserTotp.Code code = BrowserTotp.codeAt(config, unixSeconds.getAsLong());
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("code", code.value());
            response.put("validForSeconds", code.validForSeconds());
            response.put("digits", config.digits());
            return BrowserUseToolSupport.toJson(response);
        } catch (IllegalArgumentException e) {
            // parseSecret's messages never contain the seed (see BrowserTotp).
            return BrowserUseToolSupport.errorJson(
                    "The vault secret " + name + " is not a usable TOTP seed: " + e.getMessage());
        } catch (RuntimeException e) {
            // Not e.getMessage(): a vault failure must not be able to echo anything
            // secret-adjacent.
            log.warn("browser_totp failed for secret {}: {}", name, e.getClass().getSimpleName());
            return BrowserUseToolSupport.errorJson(
                    "Could not compute the TOTP code: an unexpected error occurred.");
        }
    }
}
