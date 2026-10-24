package com.agenticai.core.agents.skills;

import com.agenticai.core.agents.job.AgentJobLookup;
import com.agenticai.core.agents.tools.ToolCallLog;
import com.agenticai.core.agents.tools.ToolCallLogRepository;
import com.agenticai.core.prompts.PromptResolver;
import com.agenticai.core.prompts.PromptSlot;
import com.agenticai.core.rl.domain.Episode;
import com.agenticai.core.rl.repo.EpisodeRepository;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Skill acquisition by evidence, not by self-modification: mines this agent's recorded runs for
 * tool-call sequences that recur across DIFFERENT successful runs, and stages each as a DISABLED
 * draft skill through {@link SkillService#propose} - the same gate every agent-authored skill takes
 * (digest-pinned via {@code content_sha256}, invisible to every prompt until a human enables it).
 * Nothing here ever writes an enabled skill.
 *
 * <p><b>Sources.</b> A "successful run" is (a) a {@code SUCCEEDED} {@code agent_job} row - its
 * {@code session_id} ({@code job:<uuid>}) joins {@code tool_call_log} directly, or (b) a
 * high-reward {@code rl_episode} - its {@code [startedAt, endedAt]} window selects that session's
 * tool calls. Only calls with no {@code error_message} count toward a sequence (a call that failed
 * inside a still-succeeding run is part of the recovery, not the procedure worth teaching).
 *
 * <p><b>Sequence normalization.</b> Consecutive repeats of one tool collapse to a single step - a
 * polling loop run 12 times and the same loop run 3 times are the same procedure. Sequences shorter
 * than {@code app.skills.distillation.min-sequence-length} or longer than {@code max-sequence-length} are ignored: one call is
 * not a procedure and a very long chain is a run, not a pattern.
 *
 * <p><b>Support.</b> A pattern must appear in at least {@code app.skills.distillation.min-distinct-runs} distinct sessions
 * before it can be distilled - an accident repeated inside one run is not a skill.
 */
@Service
public class SkillDistillationService {

    // The support, the sequence length bounds, the scan sizes, the reward bar and the per-pass caps are
    // app.skills.distillation.* - see SkillDistillationProperties.

    /**
     * Marker stored in the generated description/body so a later run recognises its own proposals.
     */
    static final String DISTILLER_ACTOR = "agent:skill-distiller";

    private final AgentJobLookup jobLookup;
    private final EpisodeRepository episodeRepository;
    private final ToolCallLogRepository toolCallLogRepository;
    private final SkillService skillService;

    /** {@code app.skills.distillation.*}; a hand-built instance keeps the shipped defaults. */
    private volatile SkillDistillationProperties properties = new SkillDistillationProperties();

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setProperties(SkillDistillationProperties properties) {
        this.properties = properties == null ? new SkillDistillationProperties() : properties;
    }

    public SkillDistillationService(
            AgentJobLookup jobLookup,
            EpisodeRepository episodeRepository,
            ToolCallLogRepository toolCallLogRepository,
            SkillService skillService) {
        this.jobLookup = jobLookup;
        this.episodeRepository = episodeRepository;
        this.toolCallLogRepository = toolCallLogRepository;
        this.skillService = skillService;
    }

    /** One repeated tool sequence and the evidence behind it. */
    public record ToolPattern(
            List<String> tools,
            int distinctSessions,
            int runs,
            double meanReward,
            List<String> sampleGoals) {}

    /**
     * The outcome of one distill pass: detected patterns + which drafts were staged or skipped
     * (with reason).
     */
    public record DistillResult(
            List<ToolPattern> patterns,
            List<String> proposedSkillNames,
            Map<String, String> skipped) {}

    /**
     * The detect half - read-only preview for the console. Patterns are ordered strongest evidence
     * first (distinct sessions, then runs).
     */
    @Transactional(readOnly = true)
    public List<ToolPattern> detectPatterns(UUID agentId) {
        Map<String, Agg> patterns = new LinkedHashMap<>();
        collectFromJobs(agentId, patterns);
        collectFromEpisodes(agentId, patterns);
        return patterns.values().stream()
                .filter(a -> a.sessions.size() >= properties.getMinDistinctRuns())
                .map(Agg::toPattern)
                .sorted(
                        Comparator.comparingInt(ToolPattern::distinctSessions)
                                .reversed()
                                .thenComparingInt(ToolPattern::runs)
                                .reversed())
                .toList();
    }

    /**
     * Detect + stage: each qualifying pattern becomes a disabled draft skill awaiting admin review.
     * Idempotent by name - the generated name derives from the sequence signature, so a second run
     * hits {@code propose}'s duplicate check and records the pattern as skipped rather than
     * double-staging.
     */
    @Transactional
    public DistillResult distill(UUID agentId) {
        List<ToolPattern> patterns = detectPatterns(agentId);
        List<String> proposed = new ArrayList<>();
        Map<String, String> skipped = new LinkedHashMap<>();
        int staged = 0;
        for (ToolPattern pattern : patterns) {
            if (staged >= properties.getMaxProposalsPerRun()) {
                skipped.put(signature(pattern.tools()), "over the per-run proposal cap");
                continue;
            }
            String name = skillName(pattern.tools());
            try {
                skillService.propose(
                        name, description(pattern, agentId), body(pattern, agentId), DISTILLER_ACTOR);
                proposed.add(name);
                staged++;
            } catch (SkillValidationException e) {
                skipped.put(signature(pattern.tools()), name + ": " + e.getMessage());
            }
        }
        return new DistillResult(patterns, proposed, skipped);
    }

    // ------------------------------------------------------------------ sources

    private void collectFromJobs(UUID agentId, Map<String, Agg> patterns) {
        List<AgentJobLookup.JobFacts> succeeded =
                jobLookup.recentSucceeded(agentId, properties.getMaxJobsScanned());
        for (AgentJobLookup.JobFacts job : succeeded) {
            List<ToolCallLog> calls =
                    toolCallLogRepository.findByAgentIdAndSessionIdOrderByStartedAtAsc(
                            agentId, job.sessionId());
            List<String> sequence = sequenceOf(calls);
            if (sequence == null) {
                continue;
            }
            Agg agg = patterns.computeIfAbsent(signature(sequence), s -> new Agg(sequence));
            agg.sessions.add(job.sessionId());
            agg.runs++;
            if (job.goal() != null && agg.goals.size() < properties.getMaxSampleGoals()) {
                agg.goals.add(truncate(job.goal()));
            }
        }
    }

    private void collectFromEpisodes(UUID agentId, Map<String, Agg> patterns) {
        // Oldest-first listing; scan only the most recent tail.
        List<Episode> rewarded =
                episodeRepository.findByAgentIdAndRewardIsNotNullOrderByStartedAtAsc(agentId);
        int scanned = 0;
        for (int i = rewarded.size() - 1;
                i >= 0 && scanned < properties.getMaxEpisodesScanned();
                i--, scanned++) {
            Episode e = rewarded.get(i);
            if (e.getReward() == null || e.getReward().compareTo(properties.getMinEpisodeReward()) < 0) {
                continue;
            }
            List<ToolCallLog> calls =
                    toolCallLogRepository
                            .findByAgentIdAndSessionIdOrderByStartedAtAsc(agentId, e.getSessionId())
                            .stream()
                            .filter(
                                    c ->
                                            !c.getStartedAt().isBefore(e.getStartedAt())
                                                    && (e.getEndedAt() == null
                                                            || !c.getStartedAt()
                                                                    .isAfter(e.getEndedAt())))
                            .toList();
            List<String> sequence = sequenceOf(calls);
            if (sequence == null) {
                continue;
            }
            Agg agg = patterns.computeIfAbsent(signature(sequence), s -> new Agg(sequence));
            agg.sessions.add(e.getSessionId());
            agg.runs++;
            agg.rewardSum += e.getReward().doubleValue();
            agg.rewardedRuns++;
        }
    }

    /**
     * Ordered, deduped tool names of a run's successful calls; null when the run teaches nothing.
     */
    private List<String> sequenceOf(List<ToolCallLog> calls) {
        List<String> sequence = new ArrayList<>();
        for (ToolCallLog c : calls) {
            if (c.getErrorMessage() != null || c.getToolName() == null) {
                continue;
            }
            String name = c.getToolName();
            if (sequence.isEmpty() || !sequence.get(sequence.size() - 1).equals(name)) {
                sequence.add(name);
            }
        }
        if (sequence.size() < properties.getMinSequenceLength()
                || sequence.size() > properties.getMaxSequenceLength()) {
            return null;
        }
        return sequence;
    }

    // ------------------------------------------------------------------ naming + body

    /** {@code name_a->name_b}; the identity of a pattern. */
    static String signature(List<String> tools) {
        return String.join("->", tools);
    }

    /**
     * {@code distilled-<first>-<last>-<hash6>} - deterministic per sequence, legal under {@code
     * SkillMarkdownParser.NAME_PATTERN} (lowercase alnum+dash, <=64 chars), collision-safe against
     * tools named unlike a slug (the hash covers the full signature, not just the endpoints).
     */
    static String skillName(List<String> tools) {
        String first = slug(tools.get(0));
        String last = slug(tools.get(tools.size() - 1));
        String hash = HexFormat.of().formatHex(sha256(signature(tools))).substring(0, 6);
        String name = "distilled-" + first + "-" + last + "-" + hash;
        return name.length() > SkillMarkdownParser.MAX_NAME_LENGTH
                ? "distilled-" + hash + "-" + last.substring(0, Math.min(last.length(), 40))
                : name;
    }

    private static String slug(String toolName) {
        String s = toolName.toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9]+", "-");
        s = s.replaceAll("-+", "-").replaceAll("^-|-$", "");
        return s.isEmpty() ? "tool" : s;
    }

    /** Registry keys of the generated skill text (default text: {@code prompts/en/skills.distilled.*.md}). */
    static final String DESCRIPTION_KEY = "skills.distilled.description";
    static final String PURPOSE_KEY = "skills.distilled.purpose";
    static final String WHEN_TO_USE_KEY = "skills.distilled.when_to_use";
    static final String PROCEDURE_KEY = "skills.distilled.procedure";
    static final String FOOTER_KEY = "skills.distilled.footer";

    private final PromptSlot prompts = new PromptSlot();

    /** The prompt registry, so a tenant or agent override of the keys above applies; absent = shipped text. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setPromptResolver(PromptResolver resolver) {
        prompts.use(resolver);
    }

    String description(ToolPattern p, UUID agentId) {
        String seq = signature(p.tools());
        String d =
                prompts.text(
                        DESCRIPTION_KEY,
                        agentId,
                        Map.of("runs", String.valueOf(p.runs()), "sequence", seq));
        return d.length() > 200 ? d.substring(0, 197) + "..." : d;
    }

    String body(ToolPattern p, UUID agentId) {
        StringBuilder b = new StringBuilder();
        b.append(
                prompts.text(
                        PURPOSE_KEY,
                        agentId,
                        Map.of(
                                "runs", String.valueOf(p.runs()),
                                "sessions", String.valueOf(p.distinctSessions()))));
        if (!p.sampleGoals().isEmpty()) {
            b.append(prompts.text(WHEN_TO_USE_KEY, agentId));
            for (String goal : p.sampleGoals()) {
                b.append("- ").append(goal).append('\n');
            }
            b.append('\n');
        }
        b.append(prompts.text(PROCEDURE_KEY, agentId));
        for (int i = 0; i < p.tools().size(); i++) {
            b.append(i + 1).append(". `").append(p.tools().get(i)).append("`\n");
        }
        b.append(prompts.text(FOOTER_KEY, agentId));
        return b.toString();
    }

    private String truncate(String goal) {
        String g = goal.replaceAll("\\s+", " ").trim();
        int max = properties.getMaxGoalChars();
        return g.length() > max ? g.substring(0, max - 3) + "..." : g;
    }

    private static byte[] sha256(String s) {
        try {
            return MessageDigest.getInstance("SHA-256")
                    .digest(s.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Mutable accumulation while scanning; becomes a {@link ToolPattern} once support clears. */
    private static final class Agg {
        final List<String> tools;
        final Set<String> sessions = new LinkedHashSet<>();
        final List<String> goals = new ArrayList<>();
        int runs;
        double rewardSum;
        int rewardedRuns;

        Agg(List<String> tools) {
            this.tools = tools;
        }

        ToolPattern toPattern() {
            double mean = rewardedRuns > 0 ? rewardSum / rewardedRuns : 0;
            return new ToolPattern(tools, sessions.size(), runs, mean, List.copyOf(goals));
        }
    }
}
