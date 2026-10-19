package com.agenticai.core.chat;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;

/**
 * Decides what the Langfuse {@code langfuse.user.id} span attribute carries,
 * per {@code app.observability.langfuse.user-id-mode}:
 * <ul>
 *   <li>{@code user} (DEFAULT, the behaviour since the invoke_agent work): the Keycloak
 *       subject (a pseudonymous UUID, never an email) when one is known, else the tenant.
 *       Per-user traces are separable; the raw subject reaches the trace backend.</li>
 *   <li>{@code user-hash}: a salted SHA-256 of the subject, so per-user traces are still
 *       separable inside Langfuse but the identifier is not the platform's own user id and
 *       cannot be joined back to it without the salt. The hash is scoped to the tenant
 *       (tenant id is mixed in) so the same person in two tenants is two Langfuse users.
 *       Falls back to the tenant when no subject is known.</li>
 *   <li>{@code tenant}: the tenant id for every call - the original behaviour; no per-user
 *       separation at all.</li>
 * </ul>
 * A non-blank but unrecognised mode resolves to {@code tenant} (the least identifying), so a
 * typo can never silently widen what is sent; a blank mode is the default.
 *
 * <p>Pure and immutable; resolved once by {@link LlmModelSupport}. The salt
 * ({@code app.observability.langfuse.user-id-salt}) is a deployment secret: with a blank salt
 * the hash is still tenant-scoped but is dictionary-attackable by anyone who knows candidate
 * subjects, so set one wherever {@code user-hash} is enabled.
 */
public final class LangfuseUserId {

    public enum Mode {
        USER, USER_HASH, TENANT;

        /** Blank/null = {@link #USER} (the default); an unknown non-blank value = {@link #TENANT}. */
        public static Mode parse(String raw) {
            if (raw == null || raw.isBlank()) {
                return USER;
            }
            return switch (raw.trim().toLowerCase(Locale.ROOT).replace('_', '-')) {
                case "user" -> USER;
                case "user-hash", "userhash", "hash" -> USER_HASH;
                default -> TENANT;
            };
        }
    }

    /** What an unconfigured advisor (tests, hand-built clients) does: the real user. */
    public static final LangfuseUserId DEFAULT = new LangfuseUserId(Mode.USER, "");

    private static final int HASH_HEX_CHARS = 32;

    private final Mode mode;
    private final String salt;

    public LangfuseUserId(Mode mode, String salt) {
        this.mode = mode == null ? Mode.USER : mode;
        this.salt = salt == null ? "" : salt;
    }

    public static LangfuseUserId of(String configuredMode, String configuredSalt) {
        return new LangfuseUserId(Mode.parse(configuredMode), configuredSalt);
    }

    public Mode mode() {
        return mode;
    }

    /** The value for {@code langfuse.user.id}; {@code null} only when there is neither tenant nor subject. */
    public String resolve(String tenantId, String subject) {
        boolean hasTenant = tenantId != null && !tenantId.isBlank();
        boolean hasSubject = subject != null && !subject.isBlank();
        return switch (mode) {
            case TENANT -> hasTenant ? tenantId : null;
            case USER -> hasSubject ? subject : (hasTenant ? tenantId : null);
            case USER_HASH -> hasSubject ? hash(tenantId, subject) : (hasTenant ? tenantId : null);
        };
    }

    private String hash(String tenantId, String subject) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(salt.getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);
            digest.update((tenantId == null ? "" : tenantId).getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);
            digest.update(subject.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest.digest()).substring(0, HASH_HEX_CHARS);
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is mandatory on every JVM; never fall back to the raw id if it is somehow absent.
            return tenantId;
        }
    }
}
