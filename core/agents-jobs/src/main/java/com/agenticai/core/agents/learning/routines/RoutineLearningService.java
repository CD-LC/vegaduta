package com.agenticai.core.agents.learning.routines;

import com.agenticai.core.agents.learning.LearningProposalKind;
import com.agenticai.core.agents.learning.LearningProposalService;
import com.agenticai.core.agents.learning.LearningProposalService.Proposal;
import com.agenticai.core.agents.learning.LearningProposalService.Result;
import com.agenticai.core.agents.learning.routines.RoutineDetector.Obs;
import com.agenticai.core.agents.learning.routines.RoutineDetector.Routine;
import com.agenticai.core.agents.scheduledtask.ScheduledTaskDtos.TaskResponse;
import com.agenticai.core.agents.scheduledtask.ScheduledTaskService;
import com.agenticai.core.agents.skills.RoutineObservationGateway;
import com.agenticai.core.agents.skills.learning.SkillDraftScrubber;
import com.agenticai.core.security.TenantContext;
import com.agenticai.core.security.concurrent.TenantAwareExecutors;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/**
 * The "routines from habits" loop. It remembers, per person, the requests they make in chat and as Quick Tasks
 * (scrubbed, short-lived, never a request that needed scrubbing), groups requests that mean the same thing, and
 * when one group shows a rhythm - the same thing on at least 3 distinct days, at about the same time, daily, every
 * weekday or weekly - files a ROUTINE proposal whose payload is a ready scheduled task. The person approves it in
 * the Learned section and {@link RoutineApplier} creates the scheduled task; nothing is scheduled before that.
 *
 * <p>Off unless {@code app.learning.routines.enabled} and {@code app.learning.proposals.enabled} are both true
 * (the personal profile turns both on). It never touches the chat turn's stream: the gateway hands each request to
 * this bean's own bounded single worker, the tenant travelling as data (AGENTS.md rule 12), and a full queue drops
 * the observation. There is no model call: grouping is token similarity, so the cost is a few indexed reads.
 * Proposals still go through {@link LearningProposalService#claimSlot} (per-agent cooldown and daily cap).
 */
@Service
@ConditionalOnProperty(
        name = {"app.learning.proposals.enabled", "app.learning.routines.enabled"},
        havingValue = "true")
public class RoutineLearningService implements RoutineObservationGateway.Observer {

    private static final Logger log = LoggerFactory.getLogger(RoutineLearningService.class);

    static final String LOOP = "routines";
    private static final int QUEUE_CAPACITY = 32;
    private static final Duration DOUBLE_SUBMIT = Duration.ofMinutes(2);
    private static final Duration ATTEMPT_MEMORY = Duration.ofHours(24);
    private static final Duration PRUNE_EVERY = Duration.ofHours(1);
    /** Above this, a routine and an existing scheduled task are the same thing. */
    static final double SAME_AS_EXISTING = 0.7;

    private final RoutineObservationRepository observations;
    private final LearningProposalService proposals;
    private final ScheduledTaskService scheduledTasks;
    private final SkillDraftScrubber scrubber;
    private final RoutineDetector.Settings settings;
    private final int windowDays;
    private final int retentionDays;

    private final Map<String, Instant> attempted = new ConcurrentHashMap<>();
    /** Last retention sweep per tenant: the delete runs in the calling tenant's schema only. */
    private final Map<String, Long> lastPrune = new ConcurrentHashMap<>();
    private volatile Executor executor;
    private volatile Clock clock = Clock.systemUTC();
    private final ThreadPoolExecutor ownPool;

    public RoutineLearningService(
            RoutineObservationRepository observations,
            LearningProposalService proposals,
            ScheduledTaskService scheduledTasks,
            ObjectProvider<SkillDraftScrubber> platformScrubber,
            @Value("${app.learning.routines.timezone:}") String timezone,
            @Value("${app.learning.routines.similarity:0.6}") double similarity,
            @Value("${app.learning.routines.min-occurrences:3}") int minOccurrences,
            @Value("${app.learning.routines.min-distinct-days:3}") int minDistinctDays,
            @Value("${app.learning.routines.max-time-spread-minutes:120}") int maxTimeSpreadMinutes,
            @Value("${app.learning.routines.window-days:28}") int windowDays,
            @Value("${app.learning.routines.retention-days:45}") int retentionDays) {
        this.observations = observations;
        this.proposals = proposals;
        this.scheduledTasks = scheduledTasks;
        SkillDraftScrubber platform = platformScrubber.getIfAvailable();
        this.scrubber =
                platform == null
                        ? SkillDraftScrubber.basic()
                        : text -> platform.scrub(SkillDraftScrubber.basic().scrub(text));
        this.settings =
                new RoutineDetector.Settings(
                        Math.min(0.95, Math.max(0.3, similarity)),
                        Math.max(3, minOccurrences),
                        Math.max(3, minDistinctDays),
                        Math.max(15, maxTimeSpreadMinutes),
                        zoneOf(timezone));
        this.windowDays = Math.max(7, windowDays);
        this.retentionDays = Math.max(this.windowDays, retentionDays);
        this.ownPool =
                TenantAwareExecutors.newThreadPool(
                        1,
                        1,
                        30,
                        TimeUnit.SECONDS,
                        new ArrayBlockingQueue<>(QUEUE_CAPACITY),
                        TenantAwareExecutors.numberedThreadFactory("routine-learning-", true),
                        new ThreadPoolExecutor.AbortPolicy());
        this.executor = ownPool;
    }

    static ZoneId zoneOf(String timezone) {
        if (timezone == null || timezone.isBlank()) {
            return ZoneId.systemDefault();
        }
        try {
            return ZoneId.of(timezone.trim());
        } catch (RuntimeException e) {
            log.warn("app.learning.routines.timezone '{}' is not a time zone - using the server's", timezone);
            return ZoneId.systemDefault();
        }
    }

    @PostConstruct
    void install() {
        RoutineObservationGateway.install(this);
    }

    @PreDestroy
    void shutdown() {
        RoutineObservationGateway.install(null);
        ownPool.shutdown();
    }

    /** Test seam: the clock that decides whether a habit is still alive. */
    void useClock(Clock replacement) {
        this.clock = replacement;
    }

    /** Replaces the worker (tests run the work inline). */
    void useExecutor(Executor replacement) {
        this.executor = replacement;
    }

    @Override
    public boolean enabled() {
        return true;
    }

    @Override
    public void onObservation(RoutineObservationGateway.Observation o) {
        if (o == null || o.scope().isEmpty() || !RoutineDetector.eligible(o.text())) {
            return; // cheap checks on the caller's thread; everything else is on the worker
        }
        try {
            executor.execute(() -> o.scope().run(() -> process(o)));
        } catch (RejectedExecutionException e) {
            log.debug("Routine-learning queue full - skipping one request");
        }
    }

    // ---------------------------------------------------------------- worker

    /** Everything after the hand-off. Never throws. */
    void process(RoutineObservationGateway.Observation o) {
        try {
            String subject = o.scope().context().map(TenantContext::getSubject).orElse(null);
            if (subject == null || subject.isBlank()) {
                return;
            }
            String text = o.text().replaceAll("\\s+", " ").strip();
            String scrubbed = scrubber.scrub(text);
            // A request that needed scrubbing was about one specific thing (or held a secret): never kept.
            if (SkillDraftScrubber.redactions(scrubbed) > 0 || !RoutineDetector.eligible(scrubbed)) {
                return;
            }
            Set<String> tokens = RoutineDetector.tokens(scrubbed);
            String norm = String.join(" ", tokens);
            if (norm.length() > 600) {
                return;
            }
            pruneIfDue();
            List<RoutineObservation> recent =
                    observations.findTop500BySubjectAndObservedAtAfterOrderByObservedAtDesc(
                            subject, o.at().minus(Duration.ofDays(windowDays)));
            for (RoutineObservation r : recent) {
                if (r.getNormText().equals(norm)
                        && Duration.between(r.getObservedAt(), o.at()).abs().compareTo(DOUBLE_SUBMIT) < 0) {
                    return; // the same request sent twice in a row is one request
                }
            }
            RoutineObservation saved =
                    observations.save(
                            new RoutineObservation(
                                    UUID.randomUUID(), subject, o.agentId(), source(o), norm, scrubbed, o.at()));
            List<Obs> all = new ArrayList<>(recent.size() + 1);
            all.add(toObs(saved));
            for (RoutineObservation r : recent) {
                all.add(toObs(r));
            }
            for (Routine routine : RoutineDetector.detect(all, settings, clock.instant())) {
                file(subject, routine);
            }
        } catch (RuntimeException e) {
            log.warn("Routine learning failed: {}", e.getMessage());
        }
    }

    private static String source(RoutineObservationGateway.Observation o) {
        return RoutineObservationGateway.JOB.equals(o.source())
                ? RoutineObservationGateway.JOB
                : RoutineObservationGateway.CHAT;
    }

    private static Obs toObs(RoutineObservation r) {
        Set<String> tokens = Set.of(r.getNormText().split(" "));
        return new Obs(r.getAgentId(), r.getSource(), tokens, r.getRawText(), r.getObservedAt());
    }

    private void file(String subject, Routine routine) {
        String key = subject + "|" + routine.agentId() + "|" + routine.cron() + "|" + routine.tokens();
        Instant now = clock.instant();
        Instant before = attempted.get(key);
        if (before != null && Duration.between(before, now).compareTo(ATTEMPT_MEMORY) < 0) {
            return; // already filed (or declined) a moment ago; a pattern keeps matching on every new request
        }
        if (!scheduledTasksReady(routine)) {
            return;
        }
        if (!proposals.claimSlot(LOOP, routine.agentId())) {
            return;
        }
        Result result = proposals.propose(toProposal(subject, routine));
        if (attempted.size() > 2_000) {
            attempted.entrySet()
                    .removeIf(e -> Duration.between(e.getValue(), now).compareTo(ATTEMPT_MEMORY) > 0);
        }
        switch (result.outcome()) {
            case PENDING_CAP_REACHED -> {
                // Room may appear soon; try again on a later request.
            }
            default -> attempted.put(key, now);
        }
        if (result.created()) {
            log.info(
                    "Routine learning proposed '{}' for agent {}",
                    routine.scheduleInWords(),
                    routine.agentId());
        }
    }

    /** False when scheduled tasks are off, or one that does the same thing already exists. */
    private boolean scheduledTasksReady(Routine routine) {
        try {
            if (!scheduledTasks.capabilities().enabled()) {
                return false; // approving could only answer "not enabled"
            }
            for (TaskResponse t : scheduledTasks.list()) {
                if (RoutineDetector.sameIntent(routine.tokens(), t.prompt(), SAME_AS_EXISTING)) {
                    return false;
                }
            }
            return true;
        } catch (RuntimeException e) {
            log.debug("Routine proposal skipped - scheduled tasks unavailable: {}", e.getMessage());
            return false;
        }
    }

    static Proposal toProposal(String subject, Routine r) {
        String when = r.scheduleInWords();
        String body = when + ".\n" + RoutineApplier.TASK_MARKER + " " + r.prompt();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("agentId", r.agentId().toString());
        payload.put("name", RoutineApplier.nameOf(r.prompt()));
        payload.put("prompt", r.prompt());
        payload.put("frequency", r.cadence().frequency());
        payload.put("time", r.time());
        if (r.day() != null) {
            payload.put("day", r.day());
        }
        payload.put("timezone", r.timezone());
        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("signal", "routine");
        evidence.put("occurrences", r.occurrences());
        evidence.put("distinctDays", r.distinctDays());
        evidence.put("firstSeen", r.firstSeen().toString());
        evidence.put("lastSeen", r.lastSeen().toString());
        evidence.put("quote", clip(r.prompt(), 120));
        String title = when + ": " + clip(r.prompt(), 60);
        return new Proposal(
                subject, r.agentId(), LearningProposalKind.ROUTINE, title, body, payload, evidence);
    }

    static String clip(String s, int max) {
        String one = s == null ? "" : s.replaceAll("\\s+", " ").strip();
        return one.length() <= max ? one : one.substring(0, max - 3).stripTrailing() + "...";
    }

    private void pruneIfDue() {
        String tenant =
                com.agenticai.core.security.TenantContext.current()
                        .map(t -> String.valueOf(t.getTenantId()))
                        .orElse("-");
        long now = clock.millis();
        Long previous = lastPrune.get(tenant);
        if (previous != null && now - previous < PRUNE_EVERY.toMillis()) {
            return;
        }
        lastPrune.put(tenant, now);
        observations.deleteOlderThan(clock.instant().minus(Duration.ofDays(retentionDays)));
    }
}
