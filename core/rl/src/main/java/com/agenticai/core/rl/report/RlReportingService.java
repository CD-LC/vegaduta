package com.agenticai.core.rl.report;

import com.agenticai.core.rl.domain.AgentPolicy;
import com.agenticai.core.rl.domain.Episode;
import com.agenticai.core.rl.domain.PolicyArm;
import com.agenticai.core.rl.domain.TurnFeedback;
import com.agenticai.core.rl.policy.PolicyStore;
import com.agenticai.core.rl.repo.EpisodeRepository;
import com.agenticai.core.rl.repo.TurnFeedbackRepository;
import com.agenticai.core.rl.web.dto.RlSummaryResponse;
import com.agenticai.core.rl.web.dto.RlSummaryResponse.ArmStat;
import com.agenticai.core.rl.web.dto.RlSummaryResponse.FeedbackCounts;
import com.agenticai.core.rl.web.dto.RlSummaryResponse.PolicyState;
import com.agenticai.core.rl.web.dto.RlSummaryResponse.RewardPoint;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Assembles the read models behind the Agent Learning dashboard
 * ({@link #summarize}) and the admin policy editor ({@link #policyStates}).
 * The summary is read-only and never seeds anything (so a GET stays
 * side-effect-free and a new agent shows an empty dashboard); the editor
 * deliberately ensures the full per-dimension policy set exists so every
 * dimension is present to toggle.
 */
@Service
public class RlReportingService {

    private final TurnFeedbackRepository feedbackRepository;
    private final EpisodeRepository episodeRepository;
    private final PolicyStore policyStore;

    public RlReportingService(
        TurnFeedbackRepository feedbackRepository,
        EpisodeRepository episodeRepository,
        PolicyStore policyStore
    ) {
        this.feedbackRepository = feedbackRepository;
        this.episodeRepository = episodeRepository;
        this.policyStore = policyStore;
    }

    /** The dashboard read model: feedback counts + daily reward trend + arms + current policy state (no seeding). */
    @Transactional(readOnly = true)
    public RlSummaryResponse summarize(UUID agentId) {
        FeedbackCounts counts = countFeedback(feedbackRepository.findByAgentIdOrderByCreatedAtDesc(agentId));
        List<RewardPoint> trend = dailyRewardTrend(episodeRepository.findByAgentIdAndRewardIsNotNullOrderByStartedAtAsc(agentId));

        List<ArmStat> arms = new ArrayList<>();
        List<PolicyState> policies = new ArrayList<>();
        for (AgentPolicy policy : policyStore.listPolicies(agentId)) {
            policies.add(toPolicyState(policy));
            for (PolicyArm arm : policyStore.listArms(policy.getId())) {
                arms.add(toArmStat(policy, arm));
            }
        }
        return new RlSummaryResponse(counts, trend, arms, policies);
    }

    /** The admin editor read model: every dimension's flat policy state (seeded if absent). */
    @Transactional
    public List<PolicyState> policyStates(UUID agentId) {
        List<PolicyState> states = new ArrayList<>();
        for (AgentPolicy policy : policyStore.ensureAllPolicies(agentId)) {
            states.add(toPolicyState(policy));
        }
        return states;
    }

    /** One policy's flat state - used to echo back a just-updated policy. */
    public PolicyState toPolicyState(AgentPolicy policy) {
        return new PolicyState(
            policy.getDimension().name(),
            policy.getId(),
            policy.isEnabled(),
            policy.getExplorationRate(),
            policy.getAlgorithm().name()
        );
    }

    /** Groups rewarded episodes by UTC calendar day, ascending, into {date, avgReward, count}. */
    static List<RewardPoint> dailyRewardTrend(List<Episode> episodes) {
        // date -> [rewardSum, count]; LinkedHashMap keeps day order ascending
        // because episodes arrive ordered by startedAt asc.
        Map<String, double[]> byDay = new LinkedHashMap<>();
        for (Episode e : episodes) {
            if (e.getReward() == null) {
                continue;
            }
            // UTC day buckets (JAVA-040): the host zone must not change which day a reward lands in.
            String day = LocalDate.ofInstant(e.getStartedAt(), java.time.ZoneOffset.UTC).toString();
            double[] agg = byDay.computeIfAbsent(day, k -> new double[2]);
            agg[0] += e.getReward().doubleValue();
            agg[1] += 1.0;
        }
        List<RewardPoint> trend = new ArrayList<>(byDay.size());
        byDay.forEach((day, agg) -> {
            long count = (long) agg[1];
            double avg = count == 0 ? 0.0 : agg[0] / count;
            trend.add(new RewardPoint(day, avg, count));
        });
        return trend;
    }

    private static FeedbackCounts countFeedback(List<TurnFeedback> feedback) {
        long up = 0;
        long down = 0;
        long rating = 0;
        long edit = 0;
        long resolved = 0;
        long unresolved = 0;
        long ratingSum = 0;
        for (TurnFeedback f : feedback) {
            switch (f.getSignal()) {
                case THUMBS_UP -> up++;
                case THUMBS_DOWN -> down++;
                case RESOLVED -> resolved++;
                case UNRESOLVED -> unresolved++;
                case EDIT -> edit++;
                case RATING -> {
                    rating++;
                    if (f.getRating() != null) {
                        ratingSum += f.getRating();
                    }
                }
            }
        }
        Double avgRating = rating == 0 ? null : (double) ratingSum / rating;
        return new FeedbackCounts(up, down, rating, edit, resolved, unresolved, avgRating, feedback.size());
    }

    private static ArmStat toArmStat(AgentPolicy policy, PolicyArm arm) {
        return new ArmStat(
            policy.getDimension().name(),
            policy.getId(),
            arm.getId(),
            arm.getArmKey(),
            arm.getArmConfigJson(),
            arm.getContextBucket(),
            arm.getPulls(),
            arm.avgReward(),
            arm.getAlpha(),
            arm.getBeta(),
            arm.isEnabled()
        );
    }
}
