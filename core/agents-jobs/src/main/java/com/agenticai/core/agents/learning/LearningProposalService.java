package com.agenticai.core.agents.learning;

import com.agenticai.core.agents.skills.learning.SkillDraftScrubber;
import com.agenticai.core.json.JsonMappers;
import com.agenticai.core.security.TenantScope;
import com.agenticai.core.security.concurrent.TenantAwareExecutors;
import jakarta.annotation.PreDestroy;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;

/**
 * The generic store behind every "the assistant learns" loop. A loop only files a proposal here;
 * the agent PROPOSES and the person APPROVES, so nothing reaches a prompt, a schedule or a stored
 * preference before {@link #approve}. Approval is handed to the {@link LearningProposalApplier}
 * registered for the proposal's kind.
 *
 * <p>Off unless {@code app.learning.proposals.enabled=true} (on in the personal profile): then no
 * bean exists and the REST routes answer 404. Bounds: at most {@code
 * app.learning.proposals.max-pending} PENDING proposals per person, identical pending text is one
 * row, text the person already rejected is not proposed again, and PENDING proposals expire after
 * {@code ttl-days}. Everything stored is scrubbed of secrets and personal data like a drafted skill
 * is.
 *
 * <p>For loops: call {@link #claimSlot} before spending a model call (per-agent cooldown and daily
 * cap), run the work with {@link #runOffThread} (never on the request thread; the tenant is carried
 * across) and file the result with {@link #propose}, which never throws.
 */
@Service
@ConditionalOnProperty(name = "app.learning.proposals.enabled", havingValue = "true")
public class LearningProposalService {

    private static final Logger log = LoggerFactory.getLogger(LearningProposalService.class);

    static final int MAX_TITLE_CHARS = 200;
    public static final int MAX_BODY_CHARS = 2_000;
    private static final int MAX_JSON_CHARS = 4_000;

    /**
     * More redactions than this means the text is about one specific case, not general learning.
     */
    static final int MAX_REDACTIONS = 3;

    /** What {@link #propose} did. Only CREATED stored a row. */
    public enum Outcome {
        CREATED,
        DUPLICATE,
        PREVIOUSLY_REJECTED,
        PENDING_CAP_REACHED,
        TOO_SENSITIVE,
        INVALID
    }

    /**
     * What a loop files. {@code payload} and {@code evidence} are JSON-serialisable maps, may be
     * null.
     */
    public record Proposal(
            String subject,
            UUID agentId,
            LearningProposalKind kind,
            String title,
            String body,
            Map<String, Object> payload,
            Map<String, Object> evidence) {}

    /** {@code proposal} is set only for CREATED. */
    public record Result(Outcome outcome, LearningProposal proposal) {
        public boolean created() {
            return outcome == Outcome.CREATED;
        }
    }

    private final LearningProposalRepository repository;
    private final Map<LearningProposalKind, LearningProposalApplier> appliers =
            new EnumMap<>(LearningProposalKind.class);
    private final SkillDraftScrubber scrubber;
    private final int maxPending;
    private final int ttlDays;
    private final LearningThrottle throttle;
    private final ThreadPoolExecutor pool;

    public LearningProposalService(
            LearningProposalRepository repository,
            List<LearningProposalApplier> registered,
            ObjectProvider<SkillDraftScrubber> platformScrubber,
            @Value("${app.learning.proposals.max-pending:50}") int maxPending,
            @Value("${app.learning.proposals.ttl-days:30}") int ttlDays,
            @Value("${app.learning.proposals.cooldown-minutes:30}") int cooldownMinutes,
            @Value("${app.learning.proposals.max-per-agent-per-day:6}") int maxPerAgentPerDay) {
        this.repository = repository;
        for (LearningProposalApplier a : registered) {
            appliers.put(a.kind(), a);
        }
        SkillDraftScrubber platform = platformScrubber.getIfAvailable();
        this.scrubber =
                platform == null
                        ? SkillDraftScrubber.basic()
                        : text -> platform.scrub(SkillDraftScrubber.basic().scrub(text));
        this.maxPending = Math.max(1, maxPending);
        this.ttlDays = Math.max(1, ttlDays);
        this.throttle = new LearningThrottle(cooldownMinutes, maxPerAgentPerDay);
        this.pool =
                TenantAwareExecutors.newThreadPool(
                        1,
                        1,
                        30,
                        TimeUnit.SECONDS,
                        new ArrayBlockingQueue<>(16),
                        TenantAwareExecutors.numberedThreadFactory("learning-proposals-", true),
                        new ThreadPoolExecutor.AbortPolicy());
    }

    @PreDestroy
    void shutdown() {
        pool.shutdown();
    }

    // ------------------------------------------------------------ for loops

    /** Per-agent, per-loop cooldown and daily cap. Claim it before the (paid) model call. */
    public boolean claimSlot(String loop, UUID agentId) {
        return throttle.claim(loop, agentId);
    }

    /**
     * Runs learning work off the calling (request) thread on a small bounded worker, with the
     * caller's tenant re-applied. Returns false when the queue is full (the work is skipped,
     * learning is best effort).
     */
    public boolean runOffThread(Runnable work) {
        TenantScope scope = TenantScope.capture();
        try {
            pool.execute(() -> scope.run(work));
            return true;
        } catch (RejectedExecutionException e) {
            log.debug("Learning queue full - skipping one task");
            return false;
        }
    }

    /** True when a kind can be approved (an applier is registered for it). */
    public boolean canApply(LearningProposalKind kind) {
        return appliers.containsKey(kind);
    }

    /** Files a proposal for the person to review. Never throws. */
    public Result propose(Proposal in) {
        try {
            return doPropose(in);
        } catch (DataIntegrityViolationException e) {
            // Lost a race with an identical pending proposal (unique index).
            return new Result(Outcome.DUPLICATE, null);
        } catch (RuntimeException e) {
            log.warn("Learning proposal not stored: {}", e.getMessage());
            return new Result(Outcome.INVALID, null);
        }
    }

    private Result doPropose(Proposal in) {
        if (in == null || in.kind() == null || blank(in.subject()) || blank(in.body())) {
            return new Result(Outcome.INVALID, null);
        }
        String body = clean(in.body(), MAX_BODY_CHARS);
        String title =
                clean(blank(in.title()) ? firstLine(in.body()) : in.title(), MAX_TITLE_CHARS);
        if (blank(body) || blank(title)) {
            return new Result(Outcome.INVALID, null);
        }
        String scrubbed = scrubber.scrub(body);
        if (SkillDraftScrubber.redactions(scrubbed) > MAX_REDACTIONS) {
            return new Result(Outcome.TOO_SENSITIVE, null);
        }
        String payload = json(scrubJson(in.payload()));
        String evidence = json(scrubJson(in.evidence()));
        if (payload == null || evidence == null) {
            return new Result(Outcome.INVALID, null);
        }
        Instant now = Instant.now();
        expireStale(now);
        String hash = hash(scrubbed);
        if (repository.existsBySubjectAndKindAndBodyHashAndStatusIn(
                in.subject(), in.kind(), hash, Set.of(LearningProposalStatus.PENDING))) {
            return new Result(Outcome.DUPLICATE, null);
        }
        if (repository.existsBySubjectAndKindAndBodyHashAndStatusIn(
                in.subject(), in.kind(), hash, Set.of(LearningProposalStatus.REJECTED))) {
            return new Result(Outcome.PREVIOUSLY_REJECTED, null);
        }
        if (repository.countBySubjectAndStatus(in.subject(), LearningProposalStatus.PENDING)
                >= maxPending) {
            return new Result(Outcome.PENDING_CAP_REACHED, null);
        }
        LearningProposal saved =
                repository.save(
                        new LearningProposal(
                                UUID.randomUUID(),
                                in.subject(),
                                in.agentId(),
                                in.kind(),
                                scrubber.scrub(title),
                                scrubbed,
                                hash,
                                payload,
                                evidence,
                                now));
        log.info("Learning proposal {} ({}) filed for review", saved.getId(), in.kind());
        return new Result(Outcome.CREATED, saved);
    }

    // ------------------------------------------------------- for the person

    /** The person's proposals, newest first (at most 200); null filters match everything. */
    public List<LearningProposalView> list(
            String subject, LearningProposalStatus status, LearningProposalKind kind) {
        return list(subject, status, kind, null);
    }

    /**
     * {@link #list(String, LearningProposalStatus, LearningProposalKind)} capped to {@code limit}.
     */
    public List<LearningProposalView> list(
            String subject,
            LearningProposalStatus status,
            LearningProposalKind kind,
            Integer limit) {
        expireStale(Instant.now());
        List<LearningProposal> rows;
        if (status != null && kind != null) {
            rows =
                    repository.findTop200BySubjectAndStatusAndKindOrderByCreatedAtDesc(
                            subject, status, kind);
        } else if (status != null) {
            rows = repository.findTop200BySubjectAndStatusOrderByCreatedAtDesc(subject, status);
        } else if (kind != null) {
            rows = repository.findTop200BySubjectAndKindOrderByCreatedAtDesc(subject, kind);
        } else {
            rows = repository.findTop200BySubjectOrderByCreatedAtDesc(subject);
        }
        java.util.stream.Stream<LearningProposal> out = rows.stream();
        if (limit != null) {
            out = out.limit(Math.max(0, Math.min(limit, rows.size())));
        }
        return out.map(p -> LearningProposalView.of(p, canApply(p.getKind()))).toList();
    }

    /** {@link #approve(String, UUID, String)} with the proposal's own text. */
    public LearningProposalView approve(String subject, UUID id) {
        return approve(subject, id, null);
    }

    /**
     * Approves a PENDING proposal: hands it (with the person's edit of the text, if any) to its
     * kind's applier, and only when that succeeds marks it APPROVED. 404 unknown or not yours, 409
     * not pending, no applier for the kind, or the applier refused (its message is shown to the
     * person).
     */
    public LearningProposalView approve(String subject, UUID id, String editedBody) {
        LearningProposal p = pending(subject, id);
        LearningProposalApplier applier = appliers.get(p.getKind());
        if (applier == null) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Approving "
                            + p.getKind().name().toLowerCase(Locale.ROOT)
                            + " suggestions is not available yet.");
        }
        String text = p.getBody();
        if (editedBody != null) {
            text = scrubber.scrub(clean(editedBody, MAX_BODY_CHARS));
            if (blank(text)) {
                throw new ResponseStatusException(
                        HttpStatus.BAD_REQUEST, "The text cannot be empty.");
            }
        }
        try {
            applier.apply(p, text);
        } catch (LearningProposalApplyException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, e.getMessage());
        }
        p.decide(LearningProposalStatus.APPROVED, editedBody == null ? null : text, Instant.now());
        return LearningProposalView.of(repository.save(p), true);
    }

    /** Rejects a PENDING proposal; the same text is not proposed again. */
    public LearningProposalView reject(String subject, UUID id) {
        LearningProposal p = pending(subject, id);
        p.decide(LearningProposalStatus.REJECTED, null, Instant.now());
        return LearningProposalView.of(repository.save(p), canApply(p.getKind()));
    }

    // --------------------------------------------------------------- helpers

    private LearningProposal pending(String subject, UUID id) {
        LearningProposal p =
                repository
                        .findByIdAndSubject(id, subject)
                        .orElseThrow(
                                () ->
                                        new ResponseStatusException(
                                                HttpStatus.NOT_FOUND, "No such suggestion."));
        if (p.getStatus() != LearningProposalStatus.PENDING) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "This suggestion was already "
                            + p.getStatus().name().toLowerCase(Locale.ROOT)
                            + ".");
        }
        return p;
    }

    private void expireStale(Instant now) {
        try {
            repository.expirePendingBefore(
                    now.minus(Duration.ofDays(ttlDays)),
                    now,
                    LearningProposalStatus.PENDING,
                    LearningProposalStatus.EXPIRED);
        } catch (RuntimeException e) {
            log.debug("Could not expire stale proposals: {}", e.getMessage());
        }
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }

    private static String firstLine(String s) {
        String line = s.strip().split("\\R", 2)[0];
        return line.length() > 80 ? line.substring(0, 80) : line;
    }

    private static String clean(String s, int max) {
        String t = s == null ? "" : s.strip();
        return t.length() > max ? t.substring(0, max) : t;
    }

    /** Lower-cased, whitespace-collapsed sha-256, so the same advice worded the same is one row. */
    static String hash(String text) {
        String normalized = text.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").strip();
        try {
            return HexFormat.of()
                    .formatHex(
                            MessageDigest.getInstance("SHA-256")
                                    .digest(normalized.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private JsonNode scrubJson(Map<String, Object> map) {
        JsonNode node = JsonMappers.plain().valueToTree(map == null ? Map.of() : map);
        return scrubNode(node);
    }

    private JsonNode scrubNode(JsonNode node) {
        if (node.isString()) {
            return tools.jackson.databind.node.TextNode.valueOf(scrubber.scrub(node.asString()));
        }
        if (node.isObject()) {
            var out = JsonMappers.plain().createObjectNode();
            node.fields().forEachRemaining(e -> out.set(e.getKey(), scrubNode(e.getValue())));
            return out;
        }
        if (node.isArray()) {
            var out = JsonMappers.plain().createArrayNode();
            node.forEach(n -> out.add(scrubNode(n)));
            return out;
        }
        return node;
    }

    private static String json(JsonNode node) {
        try {
            String s = JsonMappers.plain().writeValueAsString(node);
            return s.length() > MAX_JSON_CHARS ? null : s;
        } catch (JacksonException e) {
            return null;
        }
    }
}
