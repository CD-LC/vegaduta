package com.agenticai.core.agents.skills.learning;

import com.agenticai.core.agents.skills.SkillLearningGateway;
import com.agenticai.core.agents.skills.SkillLifecycleService;
import com.agenticai.core.agents.skills.SkillService;
import com.agenticai.core.agents.skills.SkillValidationException;
import com.agenticai.core.agents.skills.tools.ImproveSkillToolCallback;
import com.agenticai.core.agents.skills.tools.LoadSkillToolCallback;
import com.agenticai.core.agents.skills.tools.ProposeSkillToolCallback;
import com.agenticai.core.agents.skills.tools.ReadSkillFileToolCallback;
import com.agenticai.core.agents.tools.ToolCallLog;
import com.agenticai.core.agents.tools.ToolCallLogService;
import com.agenticai.core.agents.turn.AgentLabCompletionService;
import com.agenticai.core.agents.turn.AgentLabCompletionService.LabCompletionRequest;
import com.agenticai.core.json.JsonMappers;
import com.agenticai.core.prompts.PromptResolver;
import com.agenticai.core.prompts.PromptSlot;
import com.agenticai.core.security.TenantContext;
import com.agenticai.core.security.concurrent.TenantAwareExecutors;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/**
 * The skill-learning loop: after a successful multi-step turn, one reflective model call (on the
 * agent's own model) drafts a reusable skill from what happened, and the draft is stored PENDING
 * for a human to approve, edit or reject. Nothing it writes reaches a prompt before that review.
 *
 * <p><b>Off by default</b> ({@code app.skills.learning.enabled=false}): the bean does not exist, so
 * the turn path is not touched at all ({@link SkillLearningGateway#observe} hands the reply back
 * unchanged) and nothing reads a tool log or calls a model.
 *
 * <p>When on, it runs entirely off the request thread. The tenant is captured as data when the turn
 * finishes ({@code TurnOutcome#scope}) and re-applied on the worker (AGENTS.md rule 12); the queue
 * is small and drops work when full, because a missed lesson costs nothing and a stalled chat
 * costs a lot. It also records, for every finished turn that loaded a custom skill, whether that
 * turn went well, which feeds the skill's effectiveness score.
 *
 * <p>Bounds on spend and review debt: a per-agent cooldown, a per-agent daily draft cap, the
 * workspace's pending-draft cap ({@link SkillService#MAX_PENDING_PROPOSALS}), and a transcript
 * size cap. The daily cap counts drafting attempts (model calls), not stored drafts: most attempts
 * decline, and it is the call that costs money. Both the transcript sent to the model and the draft it returns are scrubbed of secrets
 * and personal data; a draft that needed heavy scrubbing was about one specific case and is
 * dropped.
 */
@Service
@ConditionalOnProperty(name = "app.skills.learning.enabled", havingValue = "true")
public class SkillLearningService implements SkillLearningGateway.Observer {

    private static final Logger log = LoggerFactory.getLogger(SkillLearningService.class);

    /** More redactions than this in a draft means it is about one case, not a method: dropped. */
    static final int MAX_REDACTIONS_IN_DRAFT = 3;

    private static final Set<String> SKILL_META_TOOLS =
            Set.of(
                    LoadSkillToolCallback.NAME,
                    ReadSkillFileToolCallback.NAME,
                    ProposeSkillToolCallback.NAME,
                    ImproveSkillToolCallback.NAME);

    private static final int QUEUE_CAPACITY = 8;
    private static final int PER_FIELD_CHARS = 240;

    /** Registry key of the system prompt (default text: {@code prompts/en/learning.skill_draft.md}). */
    static final String PROMPT_KEY = "learning.skill_draft";

    /**
     * The shipped wording of {@link #PROMPT_KEY}, for tests and docs only: a call resolves the key through
     * the registry so a tenant or agent override applies.
     */
    static final String SYSTEM_PROMPT = PromptResolver.defaultsOnly().catalog().defaultText(PROMPT_KEY);

    private final PromptSlot prompts = new PromptSlot();

    /** The prompt registry, so a tenant or agent override of {@link #PROMPT_KEY} applies; absent = shipped text. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setPromptResolver(PromptResolver resolver) {
        prompts.use(resolver);
    }

    private final SkillLearningProperties props;
    private final ToolCallLogService toolCalls;
    private final AgentLabCompletionService lab;
    private final SkillService skills;
    private final SkillLifecycleService lifecycle;
    private final SkillDraftScrubber scrubber;

    private final Map<UUID, Instant> lastAttempt = new ConcurrentHashMap<>();
    private final Map<UUID, Deque<Instant>> draftsToday = new ConcurrentHashMap<>();
    private volatile Executor executor;
    private final ThreadPoolExecutor ownPool;
    /** Present only when {@code app.skills.learning.self-check.enabled=true}. */
    private volatile SkillSelfChecker selfChecker;

    private final AtomicLong droppedBySelfCheck = new AtomicLong();

    public SkillLearningService(
            SkillLearningProperties props,
            ToolCallLogService toolCalls,
            AgentLabCompletionService lab,
            SkillService skills,
            SkillLifecycleService lifecycle,
            ObjectProvider<SkillDraftScrubber> platformScrubber) {
        this.props = props;
        this.toolCalls = toolCalls;
        this.lab = lab;
        this.skills = skills;
        this.lifecycle = lifecycle;
        SkillDraftScrubber platform = platformScrubber.getIfAvailable();
        this.scrubber =
                platform == null
                        ? SkillDraftScrubber.basic()
                        : text -> platform.scrub(SkillDraftScrubber.basic().scrub(text));
        // Only created when the loop is on; a disabled install holds no thread.
        this.ownPool =
                props.enabled()
                        ? TenantAwareExecutors.newThreadPool(
                                1,
                                1,
                                30,
                                TimeUnit.SECONDS,
                                new ArrayBlockingQueue<>(QUEUE_CAPACITY),
                                TenantAwareExecutors.numberedThreadFactory("skill-learning-", true),
                                new ThreadPoolExecutor.AbortPolicy())
                        : null;
        this.executor = ownPool;
    }

    /** Optional: without it a draft is queued unchecked, exactly as before the self-check existed. */
    @Autowired(required = false)
    void setSelfChecker(SkillSelfChecker checker) {
        this.selfChecker = checker;
    }

    /** Drafts dropped by the self-check since startup (FAIL verdicts). */
    long droppedBySelfCheck() {
        return droppedBySelfCheck.get();
    }

    @PostConstruct
    void install() {
        SkillLearningGateway.install(this);
    }

    @PreDestroy
    void shutdown() {
        SkillLearningGateway.install(null);
        if (ownPool != null) {
            ownPool.shutdown();
        }
    }

    /** Replaces the worker (tests run the work inline). */
    void useExecutor(Executor replacement) {
        this.executor = replacement;
    }

    @Override
    public boolean enabled() {
        return props.enabled();
    }

    @Override
    public void onTurn(SkillLearningGateway.TurnOutcome outcome) {
        Executor worker = executor;
        if (!props.enabled() || outcome == null || outcome.agentId() == null
                || outcome.cancelled() || worker == null || outcome.scope().isEmpty()) {
            return;
        }
        try {
            // The scope was captured on the request thread; re-apply it on the worker.
            worker.execute(() -> outcome.scope().run(() -> process(outcome)));
        } catch (RejectedExecutionException e) {
            log.debug("Skill-learning queue full - skipping one turn");
        }
    }

    // ---------------------------------------------------------------- worker

    /** Everything after the hand-off. Never throws. */
    void process(SkillLearningGateway.TurnOutcome o) {
        try {
            List<ToolCallLog> calls =
                    // Bounded read: only this turn's calls, never the session's whole history.
                    toolCalls.findSince(o.agentId(), o.sessionId(), o.startedAt()).stream()
                            .filter(c -> c.getStartedAt() != null
                                    && !c.getStartedAt().isBefore(o.startedAt()))
                            .toList();
            Set<String> loaded = loadedSkills(calls);
            List<ToolCallLog> work =
                    calls.stream().filter(c -> !SKILL_META_TOOLS.contains(c.getToolName())).toList();
            long failed = work.stream().filter(SkillLearningService::failed).count();
            boolean good = o.completed() && failed * 2 <= work.size();
            if (!loaded.isEmpty()) {
                lifecycle.recordOutcome(loaded, good);
            }
            if (!eligibleForDraft(o, calls, work, failed, loaded)) {
                return;
            }
            if (!claimDraftSlot(o.agentId())) {
                return;
            }
            draft(o, work);
        } catch (RuntimeException e) {
            log.warn("Skill learning failed for agent {}: {}", o.agentId(), e.getMessage());
        }
    }

    private boolean eligibleForDraft(
            SkillLearningGateway.TurnOutcome o,
            List<ToolCallLog> calls,
            List<ToolCallLog> work,
            long failed,
            Set<String> loaded) {
        return o.completed()
                && notBlank(o.userMessage())
                && notBlank(o.answer())
                // Skills are switched off for this agent: a draft could never be used by it.
                && skills.settingsForTurn(o.agentId()).skillsEnabled()
                // A skill already covered this turn; fixing it is improve_skill's job.
                && loaded.isEmpty()
                // The agent already wrote (or amended) a skill itself during the turn.
                && calls.stream().noneMatch(c ->
                        ProposeSkillToolCallback.NAME.equals(c.getToolName())
                                || ImproveSkillToolCallback.NAME.equals(c.getToolName()))
                && work.size() >= props.minToolCalls()
                // A turn that mostly failed teaches what to avoid, not a method.
                && failed * 4 <= work.size();
    }

    /** Per-agent cooldown and daily cap, claimed before the (paid) model call. */
    boolean claimDraftSlot(UUID agentId) {
        Instant now = Instant.now();
        Instant last = lastAttempt.get(agentId);
        if (last != null
                && Duration.between(last, now).toMinutes() < props.cooldownMinutes()) {
            return false;
        }
        Deque<Instant> today = draftsToday.computeIfAbsent(agentId, k -> new ArrayDeque<>());
        synchronized (today) {
            Instant dayAgo = now.minus(Duration.ofHours(24));
            while (!today.isEmpty() && today.peekFirst().isBefore(dayAgo)) {
                today.pollFirst();
            }
            if (today.size() >= props.maxDraftsPerAgentPerDay()) {
                return false;
            }
            today.addLast(now);
        }
        lastAttempt.put(agentId, now);
        if (lastAttempt.size() > 5_000) {
            lastAttempt.entrySet().removeIf(e -> e.getValue().isBefore(now.minus(Duration.ofDays(1))));
            draftsToday.entrySet().removeIf(e -> e.getValue().isEmpty());
        }
        return true;
    }

    private void draft(SkillLearningGateway.TurnOutcome o, List<ToolCallLog> work) {
        String transcript = scrubber.scrub(transcript(o, work));
        String existing =
                skills.metadataForAgent(o.agentId()).stream()
                        .map(SkillService.SkillMetadata::name)
                        .limit(60)
                        .reduce((a, b) -> a + ", " + b)
                        .orElse("(none)");
        String userMessage = "Existing skills: " + existing + "\n\nTranscript:\n" + transcript;
        String subject =
                o.scope().context().map(TenantContext::getSubject).orElse("system:skill-learning");
        String reply;
        try {
            reply =
                    lab.complete(
                            new LabCompletionRequest(
                                    o.agentId(),
                                    prompts.text(PROMPT_KEY, o.agentId()),
                                    List.of(),
                                    userMessage,
                                    0.2,
                                    1500,
                                    subject,
                                    "skill-learning:" + o.sessionId()));
        } catch (RuntimeException e) {
            if (AgentLabCompletionService.isLimitFailure(e)) {
                log.debug("Skill learning skipped for agent {}: {}", o.agentId(), e.getMessage());
            } else {
                log.warn("Skill-learning model call failed for agent {}: {}", o.agentId(), e.getMessage());
            }
            return;
        }
        Optional<SkillDraftParser.Draft> parsed = SkillDraftParser.parse(reply);
        if (parsed.isEmpty()) {
            return;
        }
        SkillDraftParser.Draft d = parsed.get();
        String description = scrubber.scrub(d.description());
        String instructions = scrubber.scrub(d.instructions());
        int redactions =
                SkillDraftScrubber.redactions(description)
                        + SkillDraftScrubber.redactions(instructions);
        if (redactions > MAX_REDACTIONS_IN_DRAFT) {
            log.debug("Skill draft {} dropped: it needed {} redactions", d.name(), redactions);
            return;
        }
        // Replay the draft against this turn before anyone is asked to review it. A FAIL draft is
        // dropped, never queued; no verdict (check off, call failed) queues it unchecked.
        Optional<SkillSelfCheckVerdict> check = selfCheck(o, d.name(), instructions, subject);
        if (check.isPresent() && check.get().verdict() == SkillSelfCheckVerdict.Verdict.FAIL) {
            log.info(
                    "Skill draft '{}' for agent {} dropped by its self-check ({} dropped so far)",
                    d.name(),
                    o.agentId(),
                    droppedBySelfCheck.incrementAndGet());
            return;
        }
        try {
            skills.propose(d.name(), description, instructions, "agent:learning:" + o.agentId());
            lifecycle.markPending(
                    d.name(),
                    o.agentId(),
                    scrubber.scrub("Drafted automatically from a successful turn. " + d.rationale()));
            check.ifPresent(
                    c -> lifecycle.recordSelfCheck(d.name(), c.verdict().name(), c.reason()));
            log.info("Skill-learning stored pending draft '{}' for agent {}", d.name(), o.agentId());
        } catch (SkillValidationException e) {
            // Name taken, pending cap reached, or a field the platform rules refuse.
            log.debug("Skill draft {} not stored: {}", d.name(), e.getMessage());
        }
    }

    private Optional<SkillSelfCheckVerdict> selfCheck(
            SkillLearningGateway.TurnOutcome o, String name, String instructions, String subject) {
        SkillSelfChecker checker = selfChecker;
        if (checker == null) {
            return Optional.empty();
        }
        try {
            return checker.check(
                    o.agentId(), o.sessionId(), o.userMessage(), o.answer(), name, instructions, subject);
        } catch (RuntimeException e) {
            log.debug("Skill self-check failed for draft {}: {}", name, e.getMessage());
            return Optional.empty();
        }
    }

    // ---------------------------------------------------------------- helpers

    String transcript(SkillLearningGateway.TurnOutcome o, List<ToolCallLog> work) {
        int budget = props.maxTranscriptChars();
        StringBuilder sb = new StringBuilder();
        sb.append("User request: ").append(field(o.userMessage(), 800)).append('\n');
        int n = 1;
        for (ToolCallLog c : work) {
            String line =
                    n++ + ". " + c.getToolName() + "(" + field(c.getToolInput(), PER_FIELD_CHARS) + ") -> "
                            + (failed(c)
                                    ? "ERROR " + field(c.getErrorMessage(), PER_FIELD_CHARS)
                                    : field(c.getToolOutput(), PER_FIELD_CHARS))
                            + "\n";
            if (sb.length() + line.length() > budget - 900) {
                sb.append("... (more steps omitted)\n");
                break;
            }
            sb.append(line);
        }
        sb.append("Final answer: ").append(field(o.answer(), 800));
        // Angle brackets are stripped so transcript text cannot imitate the message's own markup.
        return sb.toString().replace('<', '(').replace('>', ')');
    }

    static Set<String> loadedSkills(List<ToolCallLog> calls) {
        Set<String> names = new LinkedHashSet<>();
        for (ToolCallLog c : calls) {
            if (!LoadSkillToolCallback.NAME.equals(c.getToolName()) || failed(c)) {
                continue;
            }
            String out = c.getToolOutput() == null ? "" : c.getToolOutput();
            if (!out.startsWith("# Skill: ")) {
                continue; // "Could not load..." / "There is no skill named..."
            }
            try {
                JsonNode in = JsonMappers.plain().readTree(c.getToolInput());
                String name = in.path("name").asText("").trim();
                if (!name.isEmpty()) {
                    names.add(name);
                }
            } catch (Exception ignored) {
                // an unreadable input is simply not counted
            }
        }
        return names;
    }

    private static boolean failed(ToolCallLog c) {
        return c.getErrorMessage() != null && !c.getErrorMessage().isBlank();
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }

    /**
     * One transcript field: cut to a generous bound (tool output can be megabytes and the
     * scrubbers are regexes), scrubbed WHOLE, then cut to its final size. Scrubbing after the
     * final cut would let a secret that straddles the cut survive as an unrecognisable prefix.
     */
    private String field(String text, int max) {
        return clip(scrubber.scrub(clip(text, max * 8)), max);
    }

    private static String clip(String text, int max) {
        if (text == null) {
            return "";
        }
        String one = text.replaceAll("\\s+", " ").strip();
        return one.length() <= max ? one : one.substring(0, max) + "...";
    }
}
