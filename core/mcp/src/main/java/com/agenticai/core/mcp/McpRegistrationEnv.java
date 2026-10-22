package com.agenticai.core.mcp;

import com.agenticai.core.crypto.EncryptionService;
import com.agenticai.core.json.JsonMappers;
import com.agenticai.core.mcp.dto.McpServerRegistrationResponse;
import com.agenticai.core.secrets.SecretReferenceResolver;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.StringUtils;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * The stored-config codec behind {@link McpRegistryService}: encrypts a registration's env map,
 * merges a partial credential edit onto the stored one, decrypts it again (flagging an unreadable
 * blob instead of failing), expands {@code {{secret.NAME}}} vault references, and reads/writes the
 * JSON columns. Created and owned by {@link McpRegistryService}; not a bean.
 */
final class McpRegistrationEnv {

    private static final Logger log = LoggerFactory.getLogger(McpRegistrationEnv.class);
    private static final ObjectMapper MAPPER = JsonMappers.plain();

    private final EncryptionService encryptionService;
    private final SecretReferenceResolver secretReferenceResolver;

    McpRegistrationEnv(
            EncryptionService encryptionService, SecretReferenceResolver secretReferenceResolver) {
        this.encryptionService = encryptionService;
        this.secretReferenceResolver = secretReferenceResolver;
    }

    String encrypt(Map<String, String> env) {
        if (env == null || env.isEmpty()) {
            return null;
        }
        return encryptionService.encrypt(writeJson(env));
    }

    /**
     * MCP-CREDENTIAL-MERGE fix (2026-08-28): the edit-credentials UI can't show or re-submit values
     * it never received back (see {@link McpServerRegistrationResponse}'s "env is deliberately
     * absent" javadoc), so a tenant retyping just the one var they're rotating previously had every
     * other stored var silently wiped by the plain {@code encryptEnv(request.env())} overwrite
     * below. Three cases, matching what the credentials page already relies on:
     *
     * <ul>
     *   <li>{@code incoming == null} - no env touched at all (e.g. only command/args changed) -
     *       keep the stored ciphertext untouched.
     *   <li>{@code incoming} non-empty on an update - merge onto the decrypted existing map
     *       (incoming wins per-key) so untouched vars survive a partial edit.
     *   <li>{@code incoming} empty ({@code {}}) - the credentials page's explicit "clear
     *       credentials" action sends exactly this to mean "wipe everything"; {@link #encrypt}
     *       already turns an empty map into {@code null}, so this case is deliberately NOT merged.
     * </ul>
     *
     * A brand-new registration ({@code wasUpdate == false}) has nothing to merge against, so it
     * always just encrypts whatever was sent.
     */
    String resolveForUpdate(
            McpServerRegistration entity, Map<String, String> incoming, boolean wasUpdate) {
        if (incoming == null) {
            return entity.getEncryptedEnv();
        }
        if (wasUpdate && !incoming.isEmpty()) {
            Map<String, String> merged =
                    new java.util.LinkedHashMap<>(decryptDetailed(entity.getEncryptedEnv()).env());
            merged.putAll(incoming);
            return encrypt(merged);
        }
        return encrypt(incoming);
    }

    /**
     * Paired {@code env} + "did decryption/parsing actually fail" outcome - see {@link
     * #decryptDetailed}.
     */
    record DecryptedEnv(Map<String, String> env, boolean corrupted) {}

    /**
     * Decrypts a registration's stored env blob, distinguishing "genuinely never configured" from
     * "ciphertext exists but decrypt/parse failed" (2026-08-05 fix) - previously both collapsed to
     * the exact same empty map with only a log line to tell them apart, so a tenant whose
     * encryption key rotated (or whose row was corrupted some other way) saw an
     * indistinguishable-from-normal "server needs configuration" failure instead of an actionable
     * "your credentials could not be read, re-enter them" one. The connect path still gets an empty
     * map either way - degrading gracefully rather than crashing the caller is still correct -
     * {@code corrupted} just makes the fact visible instead of silent (see {@code
     * McpDynamicConfig#credentialsCorrupted} and {@code DynamicMcpConnectionManager}, which
     * persists it onto the registration via {@link McpRegistryService#setCredentialsCorrupted}).
     */
    DecryptedEnv decryptDetailed(String encrypted) {
        if (!StringUtils.hasText(encrypted)) {
            // Genuinely nothing stored - not a failure of anything.
            return new DecryptedEnv(Collections.emptyMap(), false);
        }
        try {
            String json = encryptionService.decrypt(encrypted);
            return new DecryptedEnv(
                    MAPPER.readValue(json, new TypeReference<Map<String, String>>() {}), false);
        } catch (Exception e) {
            log.error(
                    "Failed to decrypt/parse stored MCP server env - treating as empty and flagging"
                            + " credentialsCorrupted",
                    e);
            return new DecryptedEnv(Collections.emptyMap(), true);
        }
    }

    /**
     * Expands {@code {{secret.NAME}}} in an MCP server's env/header values against the tenant
     * vault.
     *
     * <p>This is what lets someone register a GitHub or Stripe MCP server without pasting the token
     * into the registration at all: they store the token once in the vault and set {@code
     * GITHUB_TOKEN={{secret.GITHUB_PAT}}} here. Rotating the vault entry then fixes every server
     * referencing it, with no edits and no re-encryption of {@code encrypted_env}.
     *
     * <p>An unresolvable reference is logged and left as literal text rather than blanked. Blanking
     * it would hand the upstream an empty credential, which several services treat as anonymous
     * access - turning a missing secret into a confusing authorization result instead of an obvious
     * failure. The literal {@code {{secret.X}}} shows up in the connection error and names the
     * problem exactly.
     */
    java.util.Map<String, String> expandVaultReferences(
            java.util.Map<String, String> env, String registrationName) {
        if (env == null || env.isEmpty()) {
            return env;
        }
        java.util.Map<String, String> expanded = new java.util.LinkedHashMap<>();
        SecretReferenceResolver.Resolution resolution =
                secretReferenceResolver.resolveAll(env, null, expanded);
        if (resolution.hasUnresolved()) {
            log.warn(
                    "MCP server {} references vault secrets that did not resolve: {}. Add them as"
                            + " workspace-scoped secrets, or the server will start with the literal"
                            + " reference.",
                    registrationName,
                    resolution.unresolvedNames());
        }
        return expanded;
    }

    static List<String> readJsonList(String json) {
        if (!StringUtils.hasText(json)) {
            return Collections.emptyList();
        }
        try {
            return MAPPER.readValue(json, new TypeReference<List<String>>() {});
        } catch (Exception e) {
            log.error("Failed to parse stored MCP server args - treating as empty", e);
            return Collections.emptyList();
        }
    }

    static String writeJson(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (JacksonException e) {
            throw new IllegalStateException("Failed to serialize MCP server config", e);
        }
    }

    /** Suggested-fix args as JSON; an empty list (never a failure) if they cannot be serialized. */
    static String toJsonOrEmpty(List<String> args) {
        try {
            return MAPPER.writeValueAsString(args == null ? List.of() : args);
        } catch (JacksonException e) {
            log.warn("Could not serialize suggested-fix args, storing empty list", e);
            return "[]";
        }
    }
}
