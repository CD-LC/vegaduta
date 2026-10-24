package com.agenticai.core.rl.feedback;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code rl.feedback.*}: how a person's feedback on a turn becomes a reward for the bandit and a sample in the
 * preference export. They were constants in {@link FeedbackService} and {@code RlExportService}; the defaults
 * are those values, so nothing changes until an operator sets one.
 *
 * <p>Rewards are on the [0,1] scale the bandit expects; a value outside it is clamped. A star rating maps
 * linearly: 1 star is 0.0, 5 stars is 1.0 (the scale itself is fixed).
 */
@ConfigurationProperties(prefix = "rl.feedback")
public class RlFeedbackProperties {

    /** Reward for a thumbs-up. */
    private double thumbsUpReward = 1.0;
    /** Reward for a thumbs-down. */
    private double thumbsDownReward = 0.0;
    /** Reward when the person says the turn resolved their need. */
    private double resolvedReward = 1.0;
    /** Reward when the person says it did not. */
    private double unresolvedReward = 0.0;
    /** Reward for a turn the person had to edit (the arm is trained away from; the edit becomes the "chosen" side of an export pair). */
    private double editReward = 0.0;
    /** Stars (1..5) assumed for a RATING signal that carries no rating. */
    private int defaultRating = 3;
    /** In the preference export a rating at or above this is a desirable sample. */
    private int exportDesirableMinRating = 4;
    /** In the preference export a rating at or below this is an undesirable sample (between the two trains nothing). */
    private int exportUndesirableMaxRating = 2;

    public double getThumbsUpReward() {
        return thumbsUpReward;
    }

    public void setThumbsUpReward(double v) {
        this.thumbsUpReward = clamp01(v);
    }

    public double getThumbsDownReward() {
        return thumbsDownReward;
    }

    public void setThumbsDownReward(double v) {
        this.thumbsDownReward = clamp01(v);
    }

    public double getResolvedReward() {
        return resolvedReward;
    }

    public void setResolvedReward(double v) {
        this.resolvedReward = clamp01(v);
    }

    public double getUnresolvedReward() {
        return unresolvedReward;
    }

    public void setUnresolvedReward(double v) {
        this.unresolvedReward = clamp01(v);
    }

    public double getEditReward() {
        return editReward;
    }

    public void setEditReward(double v) {
        this.editReward = clamp01(v);
    }

    public int getDefaultRating() {
        return defaultRating;
    }

    public void setDefaultRating(int v) {
        this.defaultRating = Math.max(1, Math.min(5, v));
    }

    public int getExportDesirableMinRating() {
        return exportDesirableMinRating;
    }

    public void setExportDesirableMinRating(int v) {
        this.exportDesirableMinRating = Math.max(1, Math.min(5, v));
    }

    public int getExportUndesirableMaxRating() {
        return exportUndesirableMaxRating;
    }

    public void setExportUndesirableMaxRating(int v) {
        this.exportUndesirableMaxRating = Math.max(1, Math.min(5, v));
    }

    private static double clamp01(double v) {
        return Math.max(0.0, Math.min(1.0, v));
    }
}
