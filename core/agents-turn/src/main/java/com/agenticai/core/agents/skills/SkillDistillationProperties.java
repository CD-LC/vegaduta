package com.agenticai.core.agents.skills;

import java.math.BigDecimal;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code app.skills.distillation.*}: what the skill distiller needs before it calls a repeated tool sequence a
 * pattern, and how much it reads and stages per pass. These were constants of {@link
 * SkillDistillationService}; the values here are those numbers.
 *
 * <p>{@code application.yml} repeats the defaults and {@code SkillDistillationDefaultsTest} pins the two
 * together. Registered by {@link SkillDistillationConfiguration}.
 */
@ConfigurationProperties(prefix = "app.skills.distillation")
public class SkillDistillationProperties {

    /** A sequence must appear in at least this many distinct sessions before it can be distilled. */
    private int minDistinctRuns = 3;

    /** Sequences shorter than this (one call is not a procedure) are ignored. */
    private int minSequenceLength = 2;

    /** Sequences longer than this are ignored. */
    private int maxSequenceLength = 8;

    /** Most recent succeeded jobs read per pass. */
    private int maxJobsScanned = 500;

    /** Most recent rewarded episodes read per pass. */
    private int maxEpisodesScanned = 500;

    /** An episode counts as a success when its reward is at least this. */
    private BigDecimal minEpisodeReward = new BigDecimal("0.60");

    /** Draft skills staged per pass; the rest are reported as skipped. */
    private int maxProposalsPerRun = 5;

    /** Example goals kept per pattern. */
    private int maxSampleGoals = 3;

    /** Characters of one example goal. */
    private int maxGoalChars = 160;

    public int getMinDistinctRuns() {
        return minDistinctRuns;
    }

    public void setMinDistinctRuns(int minDistinctRuns) {
        this.minDistinctRuns = Math.max(1, minDistinctRuns);
    }

    public int getMinSequenceLength() {
        return minSequenceLength;
    }

    public void setMinSequenceLength(int minSequenceLength) {
        this.minSequenceLength = Math.max(1, minSequenceLength);
    }

    public int getMaxSequenceLength() {
        return maxSequenceLength;
    }

    public void setMaxSequenceLength(int maxSequenceLength) {
        this.maxSequenceLength = Math.max(1, maxSequenceLength);
    }

    public int getMaxJobsScanned() {
        return maxJobsScanned;
    }

    public void setMaxJobsScanned(int maxJobsScanned) {
        this.maxJobsScanned = Math.max(1, maxJobsScanned);
    }

    public int getMaxEpisodesScanned() {
        return maxEpisodesScanned;
    }

    public void setMaxEpisodesScanned(int maxEpisodesScanned) {
        this.maxEpisodesScanned = Math.max(1, maxEpisodesScanned);
    }

    public BigDecimal getMinEpisodeReward() {
        return minEpisodeReward;
    }

    public void setMinEpisodeReward(BigDecimal minEpisodeReward) {
        this.minEpisodeReward = minEpisodeReward == null ? new BigDecimal("0.60") : minEpisodeReward;
    }

    public int getMaxProposalsPerRun() {
        return maxProposalsPerRun;
    }

    public void setMaxProposalsPerRun(int maxProposalsPerRun) {
        this.maxProposalsPerRun = Math.max(1, maxProposalsPerRun);
    }

    public int getMaxSampleGoals() {
        return maxSampleGoals;
    }

    public void setMaxSampleGoals(int maxSampleGoals) {
        this.maxSampleGoals = Math.max(0, maxSampleGoals);
    }

    public int getMaxGoalChars() {
        return maxGoalChars;
    }

    public void setMaxGoalChars(int maxGoalChars) {
        this.maxGoalChars = Math.max(4, maxGoalChars);
    }
}
