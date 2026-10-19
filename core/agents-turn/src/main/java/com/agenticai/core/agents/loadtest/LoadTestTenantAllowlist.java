package com.agenticai.core.agents.loadtest;

import com.agenticai.core.tenancy.PlatformSettingsService;
import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * The SINGLE source of truth for "is this tenant allowed to run its chat
 * turns through the load-test mock model instead of a real provider" - see
 * {@code AgentChatService#chat}'s load-test branch, which is the only
 * caller. Modeled directly on {@code core/tenancy}'s {@code
 * DemoRateLimitExemptions} (same shape: a plain comma-separated allowlist
 * read fresh from {@link PlatformSettingsService}, no DB migration, exact
 * match only, malformed entries fail closed rather than throwing).
 *
 * <p><b>This is a dangerous seam if it is ever wrong</b> - it exists so a
 * load-testing harness can hammer the real HTTP/SSE chat pipeline without
 * spending real provider budget or real per-user rate-limit headroom, which
 * only holds if it is structurally impossible for a real tenant to ever hit
 * it. That is why every design choice here favors "false" over "true" on
 * any doubt:
 * <ul>
 *   <li>Reads {@code platform.load-test.tenant-ids} - a comma-separated list
 *       of exact tenant UUIDs - via {@link PlatformSettingsService#getString}.
 *       The setting DEFAULTS TO THE EMPTY STRING (no migration seeds a
 *       non-empty value), so with zero admin action {@link
 *       #isLoadTestTenant} is {@code false} for literally every tenant that
 *       has ever existed or will ever be created - there is no "default
 *       allowlist" for an operator to accidentally leave populated.</li>
 *   <li>Gated by exact tenant ID only - never a header, query param, or any
 *       other value a real HTTP client could set. The only way into the
 *       allowlist is an admin explicitly writing a tenant UUID into this
 *       platform setting (today via direct DB/admin-console edit, same as
 *       every other {@link PlatformSettingsService}-backed key).</li>
 *   <li>Deliberately NO caching - re-reads {@link PlatformSettingsService}
 *       on every call, exactly like every other key that service exposes
 *       (see its own javadoc: "no caching...every read hits Postgres
 *       fresh"). An admin removing a tenant from this list turns load-test
 *       mode off for that tenant's very next chat call, with no TTL window
 *       during which a removed tenant could still slip through faking model
 *       output. Correctness (an instantly-effective kill) beats the latency
 *       saved by caching a setting this rarely read.</li>
 *   <li>A malformed entry (not a valid UUID) is logged and skipped, never
 *       thrown - a typo in the admin UI must never crash chat for every
 *       OTHER (real) tenant, and skipping (rather than, say, failing the
 *       whole parse) means one bad entry can't accidentally suppress the
 *       rest of a deliberately-configured allowlist either.</li>
 * </ul>
 */
@Component
public class LoadTestTenantAllowlist {

    private static final Logger log = LoggerFactory.getLogger(LoadTestTenantAllowlist.class);

    /**
     * Comma-separated list of exact tenant UUIDs. Empty/missing (the
     * default) means load-test mode is off for every tenant - see class
     * javadoc.
     */
    static final String KEY = "platform.load-test.tenant-ids";

    private final PlatformSettingsService platformSettings;

    public LoadTestTenantAllowlist(PlatformSettingsService platformSettings) {
        this.platformSettings = platformSettings;
    }

    /**
     * @param tenantId the calling turn's {@code TenantContext.getTenantId()}
     *     (a UUID string) - {@code null}/blank always returns {@code false}
     *     (an unauthenticated/system call is never eligible for load-test
     *     mode). Never throws: a tenantId that somehow isn't a well-formed
     *     UUID (should not happen in practice) simply cannot match anything
     *     in the allowlist and returns {@code false}, exactly like any other
     *     tenant not on the list.
     */
    public boolean isLoadTestTenant(String tenantId) {
        if (tenantId == null || tenantId.isBlank()) {
            return false;
        }
        // No caching - see class javadoc on why an instant, always-fresh
        // read matters more here than the latency this would save.
        String raw = platformSettings.getString(KEY, "");
        if (raw == null || raw.isBlank()) {
            // The default, and the common case forever unless an operator
            // has explicitly opted a tenant in - fast path, skips even
            // building the parsed set.
            return false;
        }
        Set<String> allowlist = parse(raw);
        if (allowlist.isEmpty()) {
            // Every entry in `raw` was malformed (see parse()) - fail
            // closed, identical to an empty/missing setting.
            return false;
        }
        // Quiet normalize (no warn-logging) - this is the CALLER's tenantId,
        // not an admin-authored setting entry, so a shape mismatch here is
        // not "a typo in the admin UI" and must not be logged as one. It
        // still just means "cannot match anything in the allowlist," same
        // fail-closed outcome either way.
        return quietNormalize(tenantId.trim())
            .map(allowlist::contains)
            .orElse(false);
    }

    /**
     * Parses the raw setting value into a normalized (lower-cased canonical
     * UUID form) set, silently dropping any entry that isn't a valid UUID -
     * logged as a warning so a typo in the admin UI is visible in the logs,
     * but never thrown (a single bad entry must not crash chat for every
     * real tenant, nor suppress the rest of a deliberately-configured list).
     */
    static Set<String> parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return Set.of();
        }
        return Arrays.stream(raw.split(","))
            .map(String::trim)
            .filter(s -> !s.isEmpty())
            .flatMap(s -> {
                Optional<String> normalized = quietNormalize(s);
                if (normalized.isEmpty()) {
                    log.warn(
                        "Platform setting '{}' contains a malformed (non-UUID) tenant id entry '{}' - "
                            + "skipping it. This never fails a chat turn; fix the entry via the "
                            + "platform-maintenance admin UI.",
                        KEY, s
                    );
                }
                return normalized.stream();
            })
            .collect(Collectors.toUnmodifiableSet());
    }

    private static Optional<String> quietNormalize(String candidate) {
        try {
            return Optional.of(UUID.fromString(candidate).toString().toLowerCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }
}
