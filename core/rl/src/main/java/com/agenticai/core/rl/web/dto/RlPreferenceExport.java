package com.agenticai.core.rl.web.dto;

import java.util.List;
import java.util.UUID;

/**
 * Offline preference-optimization training data reconstructed from this agent's
 * human feedback + recorded episodes, for the out-of-process Python trainer.
 * Two current-standard formats side by side:
 *
 * <ul>
 *   <li><b>DPO pairs</b> (Direct Preference Optimization) - from EDIT feedback,
 *       where the human's corrected text is the {@code chosen} completion and
 *       the agent's original answer is the {@code rejected} one for the same
 *       prompt.</li>
 *   <li><b>KTO samples</b> (Kahneman-Tversky Optimization) - from thumbs /
 *       ratings / resolution, which are UNPAIRED binary signals: each is one
 *       {@code (prompt, completion, desirable)} sample, no matching counter-
 *       example required. This captures the bulk of real feedback (a thumbs-up
 *       has no paired rejected answer), which pairwise DPO alone would discard.</li>
 * </ul>
 *
 * <p>Both are derived by joining {@code turn_feedback} → its {@code rl_episode}
 * (agent+session+turnRef) → that episode's {@code user}/{@code assistant}
 * trajectory steps for the prompt/completion text. A neutral signal (a 3-star
 * rating) contributes to neither.
 */
public record RlPreferenceExport(
    UUID agentId,
    int dpoCount,
    int ktoCount,
    List<DpoPair> dpo,
    List<KtoSample> kto
) {

    /** A DPO preference pair: {@code chosen} is preferred over {@code rejected} for {@code prompt}. */
    public record DpoPair(String prompt, String chosen, String rejected) {
    }

    /**
     * A KTO sample: {@code completion} for {@code prompt} was {@code desirable}
     * (true) or not (false). {@code source} is the originating feedback signal
     * (e.g. {@code THUMBS_UP}, {@code RATING}, {@code RESOLVED}) for traceability.
     */
    public record KtoSample(String prompt, String completion, boolean desirable, String source) {
    }
}
