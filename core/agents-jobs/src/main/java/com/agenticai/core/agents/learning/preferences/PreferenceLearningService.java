package com.agenticai.core.agents.learning.preferences;

import com.agenticai.core.agents.learning.LearningProposalKind;
import com.agenticai.core.agents.learning.LearningProposalService;
import com.agenticai.core.agents.learning.LearningProposalService.Proposal;
import com.agenticai.core.agents.learning.LearningProposalService.Result;
import com.agenticai.core.agents.learning.LearningProposalStatus;
import com.agenticai.core.agents.learning.LearningProposalView;
import com.agenticai.core.agents.learning.preferences.PreferenceCatalog.Signal;
import com.agenticai.core.agents.skills.PreferenceSignalGateway;
import com.agenticai.core.approval.ApprovalRequest;
import com.agenticai.core.approval.ApprovalHistoryService;
import com.agenticai.core.approval.ApprovalStatus;
import com.agenticai.core.rl.feedback.TurnFeedbackRecordedEvent;
import com.agenticai.core.security.TenantContext;
import com.agenticai.core.security.TenantScope;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

/**
 * The "preferences from behavior" learning loop. It COUNTS what the person keeps doing and, once the same signal has
 * repeated {@code app.learning.preferences.threshold} times within {@code window-days}, files a PREFERENCE proposal for
 * the person to approve (the shipped applier appends it to the {@code user_profile} memory block). Nothing here reaches a
 * prompt before that approval, and nothing here calls a model: the wording is a fixed template per signal.
 *
 * <p>Signals: (1) explicit style requests in chat messages ("shorter", "no emojis", "reply in Hindi"), seen through
 * {@link PreferenceSignalGateway}; (2) the same requests written in the comment of a thumbs/rating on a turn; (3) the
 * person's own approve/reject decisions in the approval inbox, scanned at most once per cooldown per agent.
 *
 * <p>Approval patterns are informational only: the proposal says what the person tends to do and points at the agent's
 * autonomy setting, which stays the person's own decision there. Nothing here approves, skips or lowers any approval
 * requirement. Hand-off to the worker always carries the tenant (AGENTS.md rule 12); the chat turn only pays for a
 * regex over a short message and a queue offer.
 */
@Service
@ConditionalOnProperty(
        name = {"app.learning.proposals.enabled", "app.learning.preferences.enabled"},
        havingValue = "true")
public class PreferenceLearningService implements PreferenceSignalGateway.Observer {

    private static final Logger log = LoggerFactory.getLogger(PreferenceLearningService.class);

    static final String LOOP_APPROVALS = "preferences-approvals";

    /** A decision made less than this long after the request was created was automatic, not the person's. */
    static final Duration MIN_HUMAN_DECISION_DELAY = Duration.ofSeconds(2);

    private static final int MAX_APPROVAL_PROPOSALS_PER_SCAN = 3;

    private final PreferenceSignalRepository signals;
    private final LearningProposalService proposals;
    private final ObjectProvider<ApprovalHistoryService> approvals;
    private final int threshold;
    private final int windowDays;

    public PreferenceLearningService(
            PreferenceSignalRepository signals,
            LearningProposalService proposals,
            ObjectProvider<ApprovalHistoryService> approvals,
            @Value("${app.learning.preferences.threshold:3}") int threshold,
            @Value("${app.learning.preferences.window-days:30}") int windowDays) {
        this.signals = signals;
        this.proposals = proposals;
        this.approvals = approvals;
        this.threshold = Math.max(2, threshold);
        this.windowDays = Math.min(Math.max(1, windowDays), 365);
    }

    @PostConstruct
    void install() {
        PreferenceSignalGateway.install(this);
    }

    @PreDestroy
    void uninstall() {
        PreferenceSignalGateway.install(null);
    }

    @Override
    public boolean enabled() {
        return true;
    }

    // ---------------------------------------------------------------- entry points (request thread: stay cheap)

    @Override
    public void onUserMessage(UUID agentId, String sessionId, String userMessage, TenantScope scope) {
        String subject = subjectOf(scope);
        if (subject == null || agentId == null) {
            return;
        }
        Map<String, Signal> found = PreferenceCatalog.detect(userMessage);
        boolean scanApprovals = proposals.claimSlot(LOOP_APPROVALS, agentId);
        if (found.isEmpty() && !scanApprovals) {
            return;
        }
        // Re-apply the tenant on this thread so runOffThread captures it, then the worker re-applies it again.
        scope.run(
                () ->
                        proposals.runOffThread(
                                () -> {
                                    if (!found.isEmpty()) {
                                        recordAndEvaluate(subject, agentId, sessionId, "chat", found);
                                    }
                                    if (scanApprovals) {
                                        scanApprovals(subject, agentId);
                                    }
                                }));
    }

    /** A thumbs/rating comment can carry the same style request as a chat message. */
    @EventListener
    public void onFeedback(TurnFeedbackRecordedEvent event) {
        Map<String, Signal> found = PreferenceCatalog.detect(event.getComment());
        if (found.isEmpty() || event.getSubject() == null || event.getSubject().isBlank()) {
            return;
        }
        String subject = event.getSubject();
        proposals.runOffThread(
                () -> recordAndEvaluate(subject, event.getAgentId(), event.getSessionId(), "feedback", found));
    }

    // ---------------------------------------------------------------- worker

    /** Stores one row per signal, then files a proposal for every signal that has now repeated enough. Never throws. */
    void recordAndEvaluate(
            String subject, UUID agentId, String sessionId, String source, Map<String, Signal> found) {
        try {
            Instant now = Instant.now();
            Instant since = now.minus(Duration.ofDays(windowDays));
            signals.deleteOlderThan(subject, now.minus(Duration.ofDays(2L * windowDays)));
            for (Signal s : found.values()) {
                signals.save(new PreferenceSignal(subject, agentId, s.key(), source, now));
                long seen = signals.countBySubjectAndSignalKeyAndCreatedAtAfter(subject, s.key(), since);
                if (seen >= threshold) {
                    fileStyleProposal(subject, s, seen, source, sessionId);
                }
            }
        } catch (RuntimeException e) {
            log.warn("Preference learning failed: {}", e.getMessage());
        }
    }

    private void fileStyleProposal(String subject, Signal s, long seen, String source, String sessionId) {
        if (alreadyKnown(subject, s.phrase())) {
            return;
        }
        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("signal", s.key());
        evidence.put("count", seen);
        evidence.put("windowDays", windowDays);
        evidence.put("source", source);
        if (sessionId != null) {
            evidence.put("sessionId", sessionId);
        }
        file(
                subject,
                "You asked for this " + seen + " times in the last " + windowDays + " days",
                s.phrase(),
                evidence);
    }

    /** True when this exact preference is already waiting for a decision or already approved. */
    private boolean alreadyKnown(String subject, String body) {
        String wanted = normalize(body);
        for (LearningProposalStatus status :
                List.of(LearningProposalStatus.PENDING, LearningProposalStatus.APPROVED)) {
            for (LearningProposalView v : proposals.list(subject, status, LearningProposalKind.PREFERENCE)) {
                if (normalize(v.body()).equals(wanted)) {
                    return true;
                }
            }
        }
        return false;
    }

    private void file(String subject, String title, String body, Map<String, Object> evidence) {
        Result r =
                proposals.propose(
                        new Proposal(subject, null, LearningProposalKind.PREFERENCE, title, body, null, evidence));
        if (r.created()) {
            log.info("Preference proposal filed: {}", body);
        }
    }

    // ---------------------------------------------------------------- approval patterns

    /** Counts the person's own approve/reject decisions per action type and files informational proposals. */
    void scanApprovals(String subject, UUID agentId) {
        try {
            ApprovalHistoryService history = approvals.getIfAvailable();
            if (history == null) {
                return;
            }
            Instant since = Instant.now().minus(Duration.ofDays(windowDays));
            List<ApprovalRequest> decided = history.decidedBy(subject, since);
            List<ApprovalPattern> patterns = approvalPatterns(decided, threshold);
            int filed = 0;
            for (ApprovalPattern p : patterns) {
                if (filed >= MAX_APPROVAL_PROPOSALS_PER_SCAN) {
                    break;
                }
                String type = p.actionType().length() > 60 ? p.actionType().substring(0, 60) : p.actionType();
                if (!type.matches("[A-Za-z0-9._:-]+")) {
                    continue; // free-form action names never reach a prompt-bound proposal
                }
                String body;
                String title;
                if (p.approves()) {
                    body = "Usually approves \"" + type + "\" requests when asked (informational: approvals are still asked for)";
                    title = "You approved \"" + type + "\" " + p.count() + " times in the last " + windowDays
                            + " days, never rejected. Approval rules do not change; to get fewer prompts, review the"
                            + " agent's autonomy level in its settings.";
                } else {
                    body = "Usually rejects \"" + type + "\" requests (informational: approvals are still asked for)";
                    title = "You rejected \"" + type + "\" " + p.count() + " times in the last " + windowDays
                            + " days, never approved. Approval rules do not change.";
                }
                if (alreadyKnown(subject, body)) {
                    continue;
                }
                Map<String, Object> evidence = new LinkedHashMap<>();
                evidence.put("signal", p.approves() ? "approval-pattern:approved" : "approval-pattern:rejected");
                evidence.put("actionType", type);
                evidence.put("count", p.count());
                evidence.put("windowDays", windowDays);
                evidence.put("agentId", String.valueOf(agentId));
                file(subject, title.length() > 200 ? title.substring(0, 200) : title, body, evidence);
                filed++;
            }
        } catch (RuntimeException e) {
            log.warn("Approval-pattern scan failed: {}", e.getMessage());
        }
    }

    /** One action type the person decides the same way every time. */
    record ApprovalPattern(String actionType, boolean approves, int count) {}

    /**
     * Pure counting. Only decisions the person made count: PENDING rows and automatic approvals (decided at the moment
     * of creation) are skipped. An action type is a pattern when it was decided {@code threshold} times or more and
     * never the other way round. Most frequent first.
     */
    static List<ApprovalPattern> approvalPatterns(List<ApprovalRequest> decided, int threshold) {
        Map<String, int[]> counts = new HashMap<>(); // [approved, rejected]
        for (ApprovalRequest r : decided) {
            if (r.getActionType() == null || r.getStatus() == null || r.getStatus() == ApprovalStatus.PENDING) {
                continue;
            }
            if (r.getCreatedAt() != null
                    && r.getDecidedAt() != null
                    && Duration.between(r.getCreatedAt(), r.getDecidedAt()).compareTo(MIN_HUMAN_DECISION_DELAY) < 0) {
                continue;
            }
            int[] c = counts.computeIfAbsent(r.getActionType().toLowerCase(Locale.ROOT), k -> new int[2]);
            if (r.getStatus() == ApprovalStatus.REJECTED) {
                c[1]++;
            } else {
                c[0]++; // APPROVED, or approved and then failed to execute
            }
        }
        List<ApprovalPattern> out = new ArrayList<>();
        counts.forEach(
                (type, c) -> {
                    if (c[0] >= threshold && c[1] == 0) {
                        out.add(new ApprovalPattern(type, true, c[0]));
                    } else if (c[1] >= threshold && c[0] == 0) {
                        out.add(new ApprovalPattern(type, false, c[1]));
                    }
                });
        out.sort((a, b) -> b.count() != a.count() ? Integer.compare(b.count(), a.count()) : a.actionType().compareTo(b.actionType()));
        return out;
    }

    // ---------------------------------------------------------------- helpers

    private static String subjectOf(TenantScope scope) {
        if (scope == null || scope.isEmpty()) {
            return null;
        }
        String subject = scope.context().map(TenantContext::getSubject).orElse(null);
        return subject == null || subject.isBlank() ? null : subject;
    }

    private static String normalize(String text) {
        return text == null ? "" : text.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").strip();
    }
}
