package com.agenticai.core.agents.skills;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.agents.job.AgentJobLookup;
import com.agenticai.core.agents.job.AgentJobLookup.JobFacts;
import com.agenticai.core.agents.tools.ToolCallLog;
import com.agenticai.core.agents.tools.ToolCallLogRepository;
import com.agenticai.core.rl.repo.EpisodeRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * What the skill distiller needs before it calls a repeated tool sequence a pattern ({@code
 * app.skills.distillation.*}): the defaults are the old constants (3 distinct runs, 2 to 8 tools, 500 jobs and
 * episodes scanned, reward 0.60, 5 proposals, 3 sample goals of 160 characters).
 */
class SkillDistillationLimitsTest {

    private final UUID agent = UUID.randomUUID();
    private final AgentJobLookup jobs = mock(AgentJobLookup.class);
    private final EpisodeRepository episodes = mock(EpisodeRepository.class);
    private final ToolCallLogRepository calls = mock(ToolCallLogRepository.class);
    private final SkillService skills = mock(SkillService.class);
    private final SkillDistillationService service = new SkillDistillationService(jobs, episodes, calls, skills);

    private static ToolCallLog call(String tool) {
        Instant now = Instant.now();
        return new ToolCallLog(UUID.randomUUID(), "s", tool, "{}", "ok", null, now, now);
    }

    /** {@code runs} successful jobs, each calling search then summarise. */
    private void jobsWithTheSameSequence(int runs, String goal) {
        List<JobFacts> facts = new java.util.ArrayList<>();
        for (int i = 0; i < runs; i++) {
            String session = "session-" + i;
            facts.add(new JobFacts(UUID.randomUUID(), agent, session, goal, "SUCCEEDED", true, null));
            when(calls.findByAgentIdAndSessionIdOrderByStartedAtAsc(agent, session))
                    .thenReturn(List.of(call("search"), call("summarise")));
        }
        when(jobs.recentSucceeded(eq(agent), anyInt())).thenReturn(facts);
        when(episodes.findByAgentIdAndRewardIsNotNullOrderByStartedAtAsc(agent)).thenReturn(List.of());
    }

    @Test
    void theDefaultsAreTheOldConstants() {
        SkillDistillationProperties p = new SkillDistillationProperties();
        assertThat(p.getMinDistinctRuns()).isEqualTo(3);
        assertThat(p.getMinSequenceLength()).isEqualTo(2);
        assertThat(p.getMaxSequenceLength()).isEqualTo(8);
        assertThat(p.getMaxJobsScanned()).isEqualTo(500);
        assertThat(p.getMaxEpisodesScanned()).isEqualTo(500);
        assertThat(p.getMinEpisodeReward()).isEqualByComparingTo(new BigDecimal("0.60"));
        assertThat(p.getMaxProposalsPerRun()).isEqualTo(5);
        assertThat(p.getMaxSampleGoals()).isEqualTo(3);
        assertThat(p.getMaxGoalChars()).isEqualTo(160);
    }

    @Test
    void twoRunsAreNotAPatternByDefault() {
        jobsWithTheSameSequence(2, "do it");

        assertThat(service.detectPatterns(agent)).isEmpty();
    }

    @Test
    void twoRunsAreAPatternWhenTheSupportIsLowered() {
        jobsWithTheSameSequence(2, "do it");
        SkillDistillationProperties p = new SkillDistillationProperties();
        p.setMinDistinctRuns(2);
        service.setProperties(p);

        assertThat(service.detectPatterns(agent)).hasSize(1);
    }

    @Test
    void aSequenceShorterThanTheConfiguredMinimumIsIgnored() {
        jobsWithTheSameSequence(3, "do it");
        SkillDistillationProperties p = new SkillDistillationProperties();
        p.setMinSequenceLength(3);
        service.setProperties(p);

        assertThat(service.detectPatterns(agent)).isEmpty();
    }

    @Test
    void theNumberOfJobsScannedIsConfigurable() {
        jobsWithTheSameSequence(3, "do it");
        SkillDistillationProperties p = new SkillDistillationProperties();
        p.setMaxJobsScanned(40);
        service.setProperties(p);

        service.detectPatterns(agent);

        verify(jobs).recentSucceeded(agent, 40);
    }

    @Test
    void theSampleGoalsAreBoundedByTheSettings() {
        jobsWithTheSameSequence(3, "a very long goal that keeps going and going");
        SkillDistillationProperties p = new SkillDistillationProperties();
        p.setMaxGoalChars(12);
        p.setMaxSampleGoals(1);
        service.setProperties(p);

        List<SkillDistillationService.ToolPattern> patterns = service.detectPatterns(agent);

        assertThat(patterns).hasSize(1);
        assertThat(patterns.get(0).sampleGoals()).containsExactly("a very lo...");
    }
}
