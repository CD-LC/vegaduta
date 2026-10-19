package com.agenticai.core.guardrails.moderation;

import com.agenticai.core.tenancy.PlatformSetting;
import com.agenticai.core.tenancy.PlatformSettingsService;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Phase P6. Resolves the admin-configured, runtime moderation policy for a
 * given {@link ModerationContext} out of {@link PlatformSettingsService}'s
 * generic {@code platform_setting} key-value store - so an operator toggles
 * packs and tunes thresholds live in the admin UI with no redeploy, exactly
 * the posture {@code SignupRateLimiter} and the other movable knobs already
 * use.
 *
 * <p>Since the 2026-08 moderation unification this service is also the WRITE
 * path for the same keys ({@link #setPackEnabled}, {@link #setThresholds},
 * {@link #setSemanticEnabled}, the master switches) and the catalogue read the
 * admin surface renders ({@link #catalogue}). One class owning the key scheme
 * for both directions is the point: the retired {@code core/platform}
 * moderation layer wrote its own tables and keys that this engine never read,
 * which is exactly the admin-writes-config-nobody-reads defect the
 * unification removed.
 *
 * <p><b>Default-safe / opt-in.</b> Every getter below defaults to "off" or
 * to the safest bound when a key is missing, so a fresh database (no
 * moderation rows at all) means the whole subsystem is inert - the platform
 * behaves exactly as it did before P6 until an admin turns something on.
 *
 * <p><b>Config key scheme</b> (all under the {@code moderation.} prefix):
 * <pre>
 *   moderation.enabled                                     (bool, default false) master switch
 *   moderation.external-classifier.enabled                 (bool, default false) highest-stakes seam
 *
 *   moderation.pack.&lt;pack&gt;.enabled                        (bool, default false) global pack default
 *   moderation.pack.&lt;pack&gt;.enabled.plan.&lt;PLAN&gt;            (bool) per-plan override
 *   moderation.pack.&lt;pack&gt;.enabled.tenant.&lt;tenantId&gt;      (bool) per-tenant override (most specific)
 *   moderation.pack.&lt;pack&gt;.semantic.enabled                (bool, default false) run LLM-judge layer
 *   moderation.pack.&lt;pack&gt;.threshold.review                (int 0-100, default 50)
 *   moderation.pack.&lt;pack&gt;.threshold.block                 (int 0-100, default 90)
 * </pre>
 * where {@code <pack>} is a {@link ModerationPolicyPack#configKey()} value
 * ({@code foul_language}, {@code pii}, ...) and the per-scope enablement
 * suffixes live on {@link ModerationPolicyScope}.
 *
 * <p><b>Enablement resolution</b> is most-specific-wins: a tenant-level key,
 * if present, beats a plan-level key, which beats the global pack default,
 * which defaults to false. "Present" means the row exists at all - so an
 * admin can enable a pack platform-wide and then explicitly opt ONE tenant
 * back out by setting its tenant key to {@code false}.
 */
@Service
public class ModerationPolicyService {

    private static final Logger log = LoggerFactory.getLogger(ModerationPolicyService.class);

    static final String MASTER_ENABLED_KEY = "moderation.enabled";
    static final String EXTERNAL_CLASSIFIER_ENABLED_KEY = "moderation.external-classifier.enabled";

    private static final int DEFAULT_REVIEW_THRESHOLD = 50;
    private static final int DEFAULT_BLOCK_THRESHOLD = 90;

    private final PlatformSettingsService settings;

    public ModerationPolicyService(PlatformSettingsService settings) {
        this.settings = settings;
    }

    /** Master opt-in. When false, {@link ModerationService} does nothing at all. */
    public boolean isModerationEnabled() {
        return settings.getBoolean(MASTER_ENABLED_KEY, false);
    }

    /**
     * Whether the highest-stakes external-classifier layer should be invoked.
     * Note this is independent of whether a REAL classifier is wired - the
     * shipped {@link NoOpExternalSafetyClassifier} detects nothing even when
     * this is true, so turning it on with only the no-op present is harmless.
     */
    public boolean isExternalClassifierEnabled() {
        return settings.getBoolean(EXTERNAL_CLASSIFIER_ENABLED_KEY, false);
    }

    /** The subset of packs enabled for this context, in enum order. */
    public List<ModerationPolicyPack> enabledPacks(ModerationContext context) {
        List<ModerationPolicyPack> enabled = new ArrayList<>();
        for (ModerationPolicyPack pack : ModerationPolicyPack.values()) {
            if (resolve(pack, context).enabled()) {
                enabled.add(pack);
            }
        }
        return enabled;
    }

    /** The full effective config for one pack in one context. */
    public PolicyPackConfig resolve(ModerationPolicyPack pack, ModerationContext context) {
        boolean enabled = resolveEnabled(pack, context);
        if (!enabled) {
            return PolicyPackConfig.disabled();
        }
        PackTuning tuning = tuning(pack);
        return new PolicyPackConfig(true, tuning.semanticEnabled(), tuning.reviewThreshold(), tuning.blockThreshold());
    }

    /**
     * The pack's semantic flag and thresholds, independent of enablement -
     * the admin thresholds panel reads this so an operator can tune a pack
     * BEFORE switching it on. Same defaults, clamping and review<=block
     * normalisation as {@link #resolve}, because it IS {@link #resolve}'s
     * threshold half.
     */
    public PackTuning tuning(ModerationPolicyPack pack) {
        String base = "moderation.pack." + pack.configKey();
        boolean semantic = settings.getBoolean(base + ".semantic.enabled", false);
        int review = clampThreshold(settings.getInt(base + ".threshold.review", DEFAULT_REVIEW_THRESHOLD), DEFAULT_REVIEW_THRESHOLD);
        int block = clampThreshold(settings.getInt(base + ".threshold.block", DEFAULT_BLOCK_THRESHOLD), DEFAULT_BLOCK_THRESHOLD);
        // A block threshold below the review threshold would make the "queue"
        // band empty and is almost certainly a misconfiguration - normalize
        // so review <= block, keeping block as the authoritative ceiling.
        if (block < review) {
            review = block;
        }
        return new PackTuning(semantic, review, block);
    }

    private boolean resolveEnabled(ModerationPolicyPack pack, ModerationContext context) {
        // NOT advisory: a pack held for legal review resolves to disabled no
        // matter what rows exist, so it can never enter enabledPacks() and
        // neither the pattern nor the semantic layer can ever be asked about
        // it. Without this line, a hand-written moderation.pack.csam.enabled
        // row would put CSAM into the loop and its safety would rest on the
        // accidents that no pattern rules exist and semantic needs a second
        // opt-in - defence by accident rather than by design.
        if (pack.requiresLegalReview()) {
            return false;
        }

        // Most specific: per-tenant. A present tenant row wins outright
        // (including an explicit opt-out to false).
        if (context != null && context.tenantId() != null && !context.tenantId().isBlank()) {
            Boolean tenantValue = presentBoolean(ModerationPolicyScope.TENANT.enabledKey(pack, context.tenantId()));
            if (tenantValue != null) {
                return tenantValue;
            }
        }
        // Next: per-plan.
        if (context != null && context.plan() != null && !context.plan().isBlank()) {
            Boolean planValue = presentBoolean(ModerationPolicyScope.PLAN.enabledKey(pack, context.plan()));
            if (planValue != null) {
                return planValue;
            }
        }
        // Fallback: global pack default (default false - deliberately NOT
        // pack.defaultEnabled(), which is catalogue metadata; see its javadoc).
        return settings.getBoolean(ModerationPolicyScope.GLOBAL.enabledKey(pack, null), false);
    }

    // ------------------------------------------------------------------ admin
    // Catalogue read + write path for the /api/platform/moderation surface
    // (core/platform's ModerationController). Everything below composes the
    // same keys the resolution above reads - by construction, since both go
    // through ModerationPolicyScope.enabledKey and the same base string.

    /**
     * Returns the boolean value only if a row for {@code key} exists at this
     * exact scope; null when absent, so callers can distinguish "explicitly
     * false" from "not set" - the distinction the most-specific-wins
     * resolution and the admin UI's inheritance display both hang on.
     */
    public Boolean explicitSetting(ModerationPolicyPack pack, ModerationPolicyScope scope, String scopeKey) {
        return presentBoolean(scope.enabledKey(pack, normalizeScopeKey(scope, scopeKey)));
    }

    /**
     * The full catalogue with each pack's effective value at one scope, for
     * the admin UI - which needs to render every pack, including ones nobody
     * has touched. Kept here rather than in core/platform so the key scheme
     * never has to be re-derived outside this class.
     *
     * <p>Effective value shown: the requested scope's own row if present,
     * else the GLOBAL row, else off. For {@code scope=TENANT} this view
     * cannot consult the plan layer in between (the catalogue is keyed by
     * tenant id alone; the tenant's plan is not known here), so a plan-level
     * override is not reflected - the runtime resolution in {@link #resolve}
     * remains the authority. Honest limitation, stated rather than guessed.
     *
     * <p>Row metadata (updatedBy/updatedAt) comes from one
     * {@code listAll()} scan rather than per-key entity lookups -
     * admin-panel volumes, same no-caching posture as
     * {@link PlatformSettingsService} itself.
     */
    public List<ModerationPackState> catalogue(ModerationPolicyScope scope, String scopeKey) {
        String normalizedKey = normalizeScopeKey(scope, scopeKey);
        Map<String, PlatformSetting> rows = new HashMap<>();
        for (PlatformSetting row : settings.listAll()) {
            rows.put(row.getKey(), row);
        }

        List<ModerationPackState> out = new ArrayList<>();
        for (ModerationPolicyPack pack : ModerationPolicyPack.values()) {
            Boolean atScope = explicitSetting(pack, scope, normalizedKey);
            Boolean atGlobal = scope == ModerationPolicyScope.GLOBAL
                ? atScope
                : explicitSetting(pack, ModerationPolicyScope.GLOBAL, null);

            boolean available = !pack.requiresLegalReview();
            boolean enabled;
            ModerationPolicyScope decidedBy;
            if (!available) {
                // Same rule the resolver enforces: a gated pack always reads
                // OFF, so the UI can never show it as protecting something it
                // is not actually protecting.
                enabled = false;
                decidedBy = null;
            } else if (atScope != null) {
                enabled = atScope;
                decidedBy = scope;
            } else if (atGlobal != null) {
                enabled = atGlobal;
                decidedBy = ModerationPolicyScope.GLOBAL;
            } else {
                enabled = false;
                decidedBy = null;
            }

            PlatformSetting decidingRow = decidedBy == null
                ? null
                : rows.get(decidedBy.enabledKey(pack, decidedBy == scope ? normalizedKey : null));
            out.add(new ModerationPackState(
                pack.configKey(),
                pack.displayName(),
                pack.description(),
                pack.enforcement().name(),
                enabled,
                pack.defaultEnabled(),
                available,
                available ? null : "Blocked pending legal review.",
                decidingRow != null ? decidingRow.getUpdatedBy() : null,
                decidingRow != null && decidingRow.getUpdatedAt() != null ? decidingRow.getUpdatedAt().toString() : null,
                decidedBy,
                atScope != null));
        }
        return out;
    }

    /**
     * Enables/disables one pack at one scope - the write half of {@link
     * #resolveEnabled}, writing exactly the key that method reads.
     *
     * @throws ModerationPolicyPackNotAvailableException when enabling a pack
     *     held for legal review (the CSAM gate, mapped to 409 by the
     *     controller). Disabling is permitted - it is a safety no-op.
     */
    public void setPackEnabled(ModerationPolicyPack pack, ModerationPolicyScope scope, String scopeKey,
                               boolean enabled, String actor) {
        if (enabled && pack.requiresLegalReview()) {
            throw new ModerationPolicyPackNotAvailableException(pack.configKey());
        }
        String key = scope.enabledKey(pack, requireScopeKey(scope, scopeKey));
        settings.update(key, Boolean.toString(enabled), actor);
        // Deliberately logged: a change to what the platform blocks is an
        // operator action that must be reconstructable later. No content is
        // logged here, only the decision.
        log.info("Moderation policy pack '{}' set to {} at scope {} '{}' by {}",
            pack.configKey(), enabled, scope, scopeKey, actor);
    }

    /**
     * Tunes one pack's review/block thresholds (0-100 ints, the units {@link
     * ModerationService} actually bands against). Null means "leave
     * unchanged". Rejects - rather than silently normalising - a merged state
     * where review > block, so the API cannot store a configuration {@link
     * #tuning} would quietly rewrite on every read.
     */
    public void setThresholds(ModerationPolicyPack pack, Integer review, Integer block, String actor) {
        PackTuning current = tuning(pack);
        int newReview = review != null ? review : current.reviewThreshold();
        int newBlock = block != null ? block : current.blockThreshold();
        requireThreshold(newReview, "reviewThreshold");
        requireThreshold(newBlock, "blockThreshold");
        if (newReview > newBlock) {
            throw new IllegalArgumentException(
                "reviewThreshold (" + newReview + ") must not be greater than blockThreshold (" + newBlock + ")");
        }
        String base = "moderation.pack." + pack.configKey();
        if (review != null) {
            settings.update(base + ".threshold.review", Integer.toString(newReview), actor);
        }
        if (block != null) {
            settings.update(base + ".threshold.block", Integer.toString(newBlock), actor);
        }
        log.info("Moderation thresholds for pack '{}' set to review>={} block>={} by {}",
            pack.configKey(), newReview, newBlock, actor);
    }

    /** Toggles one pack's optional LLM-judge layer. */
    public void setSemanticEnabled(ModerationPolicyPack pack, boolean enabled, String actor) {
        settings.update("moderation.pack." + pack.configKey() + ".semantic.enabled",
            Boolean.toString(enabled), actor);
        log.info("Moderation semantic layer for pack '{}' set to {} by {}", pack.configKey(), enabled, actor);
    }

    /**
     * The master switch. Turning moderation on platform-wide is an explicit
     * operator decision - nothing in the unification, and no default, ever
     * flips this; see the V216 migration's closing note.
     */
    public void setMasterEnabled(boolean enabled, String actor) {
        settings.update(MASTER_ENABLED_KEY, Boolean.toString(enabled), actor);
        log.info("Moderation master switch set to {} by {}", enabled, actor);
    }

    /** Toggles the highest-stakes external-classifier layer (harmless with only the no-op wired). */
    public void setExternalClassifierEnabled(boolean enabled, String actor) {
        settings.update(EXTERNAL_CLASSIFIER_ENABLED_KEY, Boolean.toString(enabled), actor);
        log.info("Moderation external-classifier switch set to {} by {}", enabled, actor);
    }

    // ---------------------------------------------------------------- helpers

    /**
     * Returns the boolean value only if a row for {@code key} actually exists;
     * null when absent, so callers can distinguish "explicitly set to false"
     * from "not set" for the most-specific-wins resolution. Implemented on
     * top of the two typed getters with sentinel defaults, avoiding a new
     * repository method on the shared settings service.
     */
    private Boolean presentBoolean(String key) {
        // If the key is absent, getBoolean returns whatever default we pass.
        // Passing opposite defaults reveals presence: a present row yields the
        // same parsed value both times; an absent row yields the two
        // different defaults.
        boolean asTrue = settings.getBoolean(key, true);
        boolean asFalse = settings.getBoolean(key, false);
        if (asTrue == asFalse) {
            return asTrue; // present and parsed to a stable value
        }
        return null; // absent
    }

    /**
     * PLAN scope keys are stored upper-case because that is how the plan
     * token reaches {@link #resolveEnabled} at runtime (from {@code
     * TenantPlan.name()}) - normalising on the way IN is what guarantees a
     * key written by the admin surface is the key the engine reads. Tenant
     * ids pass through verbatim (they are case-significant identifiers).
     */
    private static String normalizeScopeKey(ModerationPolicyScope scope, String scopeKey) {
        if (scopeKey == null) {
            return null;
        }
        String trimmed = scopeKey.trim();
        return scope == ModerationPolicyScope.PLAN ? trimmed.toUpperCase(Locale.ROOT) : trimmed;
    }

    private static String requireScopeKey(ModerationPolicyScope scope, String scopeKey) {
        String normalized = normalizeScopeKey(scope, scopeKey);
        if (scope != ModerationPolicyScope.GLOBAL && (normalized == null || normalized.isBlank())) {
            throw new IllegalArgumentException("scope " + scope + " requires a non-blank key (plan name or tenant id)");
        }
        return normalized;
    }

    private static void requireThreshold(int value, String name) {
        if (value < 0 || value > 100) {
            throw new IllegalArgumentException(name + " must be between 0 and 100 (got " + value + ")");
        }
    }

    private int clampThreshold(int value, int fallback) {
        if (value < 0 || value > 100) {
            return fallback;
        }
        return value;
    }

    /**
     * One pack's threshold/semantic tuning, independent of enablement. The
     * enabled flag deliberately lives on {@link PolicyPackConfig}, not here -
     * this record exists so the admin thresholds panel can read tuning
     * without implying anything about whether the pack runs.
     */
    public record PackTuning(boolean semanticEnabled, int reviewThreshold, int blockThreshold) {
    }
}
