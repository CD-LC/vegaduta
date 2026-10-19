package com.agenticai.core.audit;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Admin Issues portal (#425/#426) - "apply the event-driven pattern where it
 * fits, keep sync where needed". This service is called SYNCHRONOUSLY, at
 * the exact moment of failure, from three call sites: GlobalExceptionHandler's
 * catch-all (core/app), WorkflowExecutionEngine when a run reaches FAILED
 * (core/workflow), and wherever a ToolCallLog is saved with an error
 * (core/agents). It deliberately does NOT go through the Kafka/outbox event
 * pipeline (WorkflowEventPublisher) the way routine domain events do,
 * because an issue record must still be captured even if the broker itself
 * is down or the outbox relay is stalled - the exact moment something is
 * failing is the worst possible moment to depend on another moving part.
 * Every {@code record*} call here is wrapped by its own caller in a
 * best-effort try/catch (mirroring the storeOriginalFile / prompt-caching
 * "never let an observability write break the primary operation" pattern
 * used elsewhere in this codebase) so a bug in issue-recording itself can
 * never turn into a second failure on top of the first.
 */
@Service
public class PlatformIssueService {

    private static final Logger log = LoggerFactory.getLogger(PlatformIssueService.class);

    private final PlatformIssueRepository repository;

    public PlatformIssueService(PlatformIssueRepository repository) {
        this.repository = repository;
    }

    @Transactional
    public PlatformIssue record(IssueSource source, String sourceId, IssueSeverity severity, String title, String message) {
        return record(source, sourceId, severity, null, title, message);
    }

    /**
     * Category-carrying overload - lets a caller (e.g. a guarded tool callback that already knows
     * this is a SAFETY-flagged failure) set {@link IssueCategory} at record time, without disturbing
     * the 5-arg {@link #record(IssueSource, String, IssueSeverity, String, String)} overload every
     * existing synchronous call site still uses.
     */
    @Transactional
    public PlatformIssue record(IssueSource source, String sourceId, IssueSeverity severity, IssueCategory category, String title, String message) {
        String fingerprint = fingerprint(source, sourceId, title);
        Optional<PlatformIssue> existing = repository.findByFingerprint(fingerprint);
        if (existing.isPresent()) {
            PlatformIssue issue = existing.get();
            issue.recordRecurrence(message);
            return repository.save(issue);
        }
        try {
            return repository.save(new PlatformIssue(fingerprint, source, sourceId, category, severity, title, message));
        } catch (DataIntegrityViolationException raceOnUniqueFingerprint) {
            // Two threads hit the same never-before-seen fingerprint at once - the loser here just
            // treats it as a recurrence rather than propagating a spurious constraint violation.
            PlatformIssue issue = repository.findByFingerprint(fingerprint)
                .orElseThrow(() -> raceOnUniqueFingerprint);
            issue.recordRecurrence(message);
            return repository.save(issue);
        }
    }

    /**
     * {@code AiDiagnosticAnalysisService}'s (core/app) async enrichment step - attaches an
     * {@link IssueCategory} and a free-text diagnosis to an already-recorded system-detected row.
     */
    @Transactional
    public PlatformIssue attachDiagnosis(UUID id, IssueCategory category, String diagnosis) {
        PlatformIssue issue = repository.findById(id).orElseThrow(() -> new IssueNotFoundException(id));
        issue.attachDiagnosis(category, diagnosis);
        return repository.save(issue);
    }

    /**
     * #974 - "give a link for ticket creation and categories on which issue
     * falls under" - a tenant admin self-filing a support ticket from the
     * Billing & usage page. Deliberately NOT routed through {@link #record}'s
     * fingerprint-dedup logic: {@code record} exists so a repeating SYSTEM
     * failure collapses into one row with a bumped {@code occurrenceCount};
     * a human filing two separate tickets - even with an identical title,
     * e.g. two different "Can't send WhatsApp messages" reports a week apart
     * - are two distinct tickets that both deserve their own row, not one
     * being silently swallowed as a "recurrence" of the other. The random
     * UUID folded into the fingerprint guarantees that.
     */
    @Transactional
    public PlatformIssue createTicket(IssueCategory category, String title, String message) {
        String fingerprint = fingerprint(IssueSource.TENANT_REPORTED, UUID.randomUUID().toString(), title);
        return repository.save(new PlatformIssue(
            fingerprint, IssueSource.TENANT_REPORTED, null, category, IssueSeverity.WARNING, title, message
        ));
    }

    public List<PlatformIssue> list(IssueStatus status) {
        return status == null ? repository.findTop200ByOrderByLastSeenAtDesc() : repository.findTop200ByStatusOrderByLastSeenAtDesc(status);
    }

    /** Paged list: newest {@code lastSeenAt} first, {@code id} as tiebreaker. */
    public List<PlatformIssue> list(IssueStatus status, int limit, long offset) {
        var page = AuditPaging.offsetLimit(offset, limit, null);
        return status == null
            ? repository.findByOrderByLastSeenAtDescIdDesc(page)
            : repository.findByStatusOrderByLastSeenAtDescIdDesc(status, page);
    }

    /** Total issues matching the status filter (all when {@code status} is null). */
    public long count(IssueStatus status) {
        return status == null ? repository.count() : repository.countByStatus(status);
    }

    @Transactional
    public PlatformIssue acknowledge(UUID id, String actorId) {
        PlatformIssue issue = repository.findById(id).orElseThrow(() -> new IssueNotFoundException(id));
        issue.acknowledge(actorId);
        return repository.save(issue);
    }

    @Transactional
    public PlatformIssue resolve(UUID id) {
        PlatformIssue issue = repository.findById(id).orElseThrow(() -> new IssueNotFoundException(id));
        issue.resolve();
        return repository.save(issue);
    }

    /**
     * Deliberately keyed on {@code source + sourceId + title} rather than the full message - the
     * message often contains variable detail (a specific record id, a timestamp inside a stack
     * trace) that would otherwise fragment one recurring problem into many distinct fingerprints.
     * {@code title} is expected to already be the caller's own normalized "what kind of failure is
     * this" label (e.g. an exception's simple class name, or a fixed "Workflow run failed" string).
     */
    private String fingerprint(IssueSource source, String sourceId, String title) {
        String raw = source + "|" + (sourceId == null ? "" : sourceId) + "|" + (title == null ? "" : title);
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(raw.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash).substring(0, 64);
        } catch (NoSuchAlgorithmException e) {
            log.warn("SHA-256 unavailable, falling back to hashCode-based fingerprint", e);
            return Integer.toHexString(raw.hashCode());
        }
    }
}
