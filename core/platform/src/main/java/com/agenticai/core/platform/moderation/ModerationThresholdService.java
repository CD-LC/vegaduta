package com.agenticai.core.platform.moderation;

import com.agenticai.core.guardrails.moderation.ModerationPolicyPack;
import com.agenticai.core.guardrails.moderation.ModerationPolicyPackNotAvailableException;
import com.agenticai.core.guardrails.moderation.ModerationPolicyService;
import com.agenticai.core.security.edition.ConditionalOnEditionFeature;
import com.agenticai.core.security.edition.EditionFeature;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * Confidence thresholds for the probabilistic guardrail layers, behind
 * {@code GET/PUT /api/platform/moderation/thresholds}.
 *
 * <p><b>The review band is the important part.</b> A single "block above X"
 * threshold forces every uncertain call into a wrong answer: either it blocks
 * legitimate content or it allows harmful content. The band splits that -
 * below a pack's review threshold is allowed, at/above its block threshold is
 * blocked, and the span between goes to a human via the review queue.
 * Widening the band buys accuracy with reviewer time; narrowing it does the
 * reverse.
 *
 * <p><b>2026-08 unification rewrite.</b> This service previously wrote four
 * platform-wide 0.0-1.0 scalars ({@code platform.moderation.threshold.*},
 * seeded by V206) that NOTHING at runtime ever read - the live engine bands
 * against per-pack 0-100 integers ({@code
 * moderation.pack.<pack>.threshold.review|block}, defaults 50/90) resolved by
 * {@link ModerationPolicyService}. An admin surface writing config the
 * enforcement path ignores is the failure this rewrite exists to close: the
 * endpoint survives, but it now reads and writes the engine's own keys, in
 * the engine's own units, through the engine's own service (which owns the
 * key scheme for both directions). V216 deleted the four orphan rows so they
 * cannot mislead anyone browsing {@code platform_setting}.
 */
@ConditionalOnEditionFeature(EditionFeature.OPERATOR_CONSOLE)
@Service
public class ModerationThresholdService {

    private final ModerationPolicyService policy;

    public ModerationThresholdService(ModerationPolicyService policy) {
        this.policy = policy;
    }

    /**
     * The two master switches plus every tunable pack's semantic flag and
     * review/block band. Packs held for legal review are omitted: they can
     * never run (see {@link ModerationPolicyPack#requiresLegalReview()}), so
     * offering their thresholds would invite writing config nothing reads -
     * the exact defect this class was rewritten to remove.
     */
    public Thresholds current() {
        Map<String, PackThresholds> packs = new LinkedHashMap<>();
        for (ModerationPolicyPack pack : ModerationPolicyPack.values()) {
            if (pack.requiresLegalReview()) {
                continue;
            }
            ModerationPolicyService.PackTuning tuning = policy.tuning(pack);
            packs.put(pack.configKey(),
                new PackThresholds(tuning.semanticEnabled(), tuning.reviewThreshold(), tuning.blockThreshold()));
        }
        return new Thresholds(policy.isModerationEnabled(), policy.isExternalClassifierEnabled(), packs);
    }

    /**
     * Applies any subset of the tunables. Null (or an omitted pack) means
     * "leave unchanged", so a UI can send one edited field without having to
     * echo the rest back correctly - echoing is how a stale field silently
     * reverts someone else's change.
     *
     * <p>Everything is validated BEFORE anything is written (unknown pack,
     * out-of-range value, review>block after merging with current values,
     * legally-gated pack): a partial apply on a safety surface leaves the
     * operator believing something took effect that did not. The merged-value
     * check reads current values at validation time; a concurrent admin edit
     * between validate and apply could in principle shift the merge base, but
     * this is an admin panel, not a hot path, and the per-pack write path
     * re-validates as its own backstop.
     */
    public Thresholds update(Boolean moderationEnabled, Boolean externalClassifierEnabled,
                             Map<String, PackThresholdsUpdate> packs, String actor) {
        List<PackApply> toApply = new ArrayList<>();
        if (packs != null) {
            for (Map.Entry<String, PackThresholdsUpdate> e : packs.entrySet()) {
                ModerationPolicyPack pack = ModerationPolicyPack.fromConfigKey(e.getKey())
                    .orElseThrow(() -> new IllegalArgumentException("Unknown policy pack: " + e.getKey()));
                if (pack.requiresLegalReview()) {
                    // Same 409 as trying to enable the pack: tuning a category
                    // that can never run writes keys nothing reads.
                    throw new ModerationPolicyPackNotAvailableException(pack.configKey());
                }
                PackThresholdsUpdate update = e.getValue();
                if (update == null) {
                    continue;
                }
                validate(pack, update);
                toApply.add(new PackApply(pack, update));
            }
        }

        for (PackApply apply : toApply) {
            if (apply.update().reviewThreshold() != null || apply.update().blockThreshold() != null) {
                policy.setThresholds(apply.pack(), apply.update().reviewThreshold(), apply.update().blockThreshold(), actor);
            }
            if (apply.update().semanticEnabled() != null) {
                policy.setSemanticEnabled(apply.pack(), apply.update().semanticEnabled(), actor);
            }
        }
        if (moderationEnabled != null) {
            policy.setMasterEnabled(moderationEnabled, actor);
        }
        if (externalClassifierEnabled != null) {
            policy.setExternalClassifierEnabled(externalClassifierEnabled, actor);
        }
        return current();
    }

    /**
     * Mirrors {@link ModerationPolicyService#setThresholds}'s own checks so
     * the batch fails as a whole before its first write - the service-level
     * check alone would reject mid-batch, after earlier packs already landed.
     */
    private void validate(ModerationPolicyPack pack, PackThresholdsUpdate update) {
        ModerationPolicyService.PackTuning current = policy.tuning(pack);
        int review = update.reviewThreshold() != null ? update.reviewThreshold() : current.reviewThreshold();
        int block = update.blockThreshold() != null ? update.blockThreshold() : current.blockThreshold();
        requireRange(review, "reviewThreshold", pack);
        requireRange(block, "blockThreshold", pack);
        if (review > block) {
            throw new IllegalArgumentException("reviewThreshold (" + review + ") must not be greater than "
                + "blockThreshold (" + block + ") for pack '" + pack.configKey() + "'");
        }
    }

    private static void requireRange(int value, String name, ModerationPolicyPack pack) {
        if (value < 0 || value > 100) {
            throw new IllegalArgumentException(
                name + " for pack '" + pack.configKey() + "' must be between 0 and 100 (got " + value + ")");
        }
    }

    private record PackApply(ModerationPolicyPack pack, PackThresholdsUpdate update) {
    }

    /**
     * @param moderationEnabled the master switch - when false the engine does
     *     nothing at all, regardless of pack config
     * @param externalClassifierEnabled the highest-stakes external-classifier
     *     layer (harmless while only the no-op classifier is wired)
     * @param packs per-pack tuning keyed by {@link ModerationPolicyPack#configKey()}
     */
    public record Thresholds(boolean moderationEnabled, boolean externalClassifierEnabled,
                             Map<String, PackThresholds> packs) {
    }

    /**
     * @param semanticEnabled whether the pack's optional LLM-judge layer runs
     * @param reviewThreshold 0-100; at/above this but below block queues for review
     * @param blockThreshold 0-100; at/above this blocks outright
     */
    public record PackThresholds(boolean semanticEnabled, int reviewThreshold, int blockThreshold) {
    }

    /** Every field optional - null means "leave unchanged". */
    public record PackThresholdsUpdate(Boolean semanticEnabled, Integer reviewThreshold, Integer blockThreshold) {
    }
}
