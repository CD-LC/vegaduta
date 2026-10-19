package com.agenticai.core.agents.skills.learning;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Settings of the skill-learning loop ({@code app.skills.learning.*}). The loop is OFF unless
 * {@code app.skills.learning.enabled=true}; every other key only matters once it is on. {@code
 * app.skills.learning.stale-days} is read by {@code SkillLifecycleService}, which reports stale
 * skills whether or not the loop runs.
 */
@Component
public class SkillLearningProperties {

    private final boolean enabled;
    private final int minToolCalls;
    private final int cooldownMinutes;
    private final int maxDraftsPerAgentPerDay;
    private final int maxTranscriptChars;

    public SkillLearningProperties(
            @Value("${app.skills.learning.enabled:false}") boolean enabled,
            @Value("${app.skills.learning.min-tool-calls:3}") int minToolCalls,
            @Value("${app.skills.learning.cooldown-minutes:30}") int cooldownMinutes,
            @Value("${app.skills.learning.max-drafts-per-agent-per-day:3}") int maxDraftsPerAgentPerDay,
            @Value("${app.skills.learning.max-transcript-chars:6000}") int maxTranscriptChars) {
        this.enabled = enabled;
        this.minToolCalls = Math.max(2, minToolCalls);
        this.cooldownMinutes = Math.max(0, cooldownMinutes);
        this.maxDraftsPerAgentPerDay = Math.max(1, maxDraftsPerAgentPerDay);
        this.maxTranscriptChars = Math.min(Math.max(1_000, maxTranscriptChars), 20_000);
    }

    /** Whether the loop observes turns at all. Default false. */
    public boolean enabled() {
        return enabled;
    }

    /** Tool calls (skill tools not counted) a turn needs before it can teach a skill. */
    public int minToolCalls() {
        return minToolCalls;
    }

    /** Minimum time between two drafting attempts for the same agent. */
    public int cooldownMinutes() {
        return cooldownMinutes;
    }

    /** Drafting attempts (reflective model calls) per agent in any 24 hours, not stored drafts. */
    public int maxDraftsPerAgentPerDay() {
        return maxDraftsPerAgentPerDay;
    }

    /** Cap on the transcript text handed to the reflective model call. */
    public int maxTranscriptChars() {
        return maxTranscriptChars;
    }
}
