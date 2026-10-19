package com.agenticai.core.omnichannel.handoff;

import com.agenticai.core.tenancy.ListPaging;
import com.agenticai.core.omnichannel.session.ChannelMessageDirection;
import com.agenticai.core.tenancy.ListPaging;
import com.agenticai.core.omnichannel.session.ChannelMessageLog;
import com.agenticai.core.omnichannel.session.ChannelSession;
import com.agenticai.core.omnichannel.session.ChannelType;
import com.agenticai.core.omnichannel.session.HandoffState;
import com.agenticai.core.security.TenantContext;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The operator inbox's REST surface (live human handoff, migration V214):
 *
 * <ul>
 *   <li>{@code GET  /api/handoff/inbox} - every conversation needing a
 *       person (AWAITING_HUMAN + HUMAN), newest activity first</li>
 *   <li>{@code GET  /api/handoff/sessions/{id}/transcript} - one
 *       conversation's two-way transcript (admin-only, see below)</li>
 *   <li>{@code POST /api/handoff/sessions/{id}/escalate} - flag a
 *       conversation as needing a person (admin-only)</li>
 *   <li>{@code POST /api/handoff/sessions/{id}/take-over} - claim it;
 *       agent + workflow trigger go silent (admin-only)</li>
 *   <li>{@code POST /api/handoff/sessions/{id}/reply} - speak as a human
 *       through the channel's existing gated send path (admin-only)</li>
 *   <li>{@code POST /api/handoff/sessions/{id}/release} - hand it back to
 *       the agent (admin-only)</li>
 * </ul>
 *
 * <p><b>Access control - two deliberate deviations from the house pattern,
 * both forced by scope boundaries and both fail-closed:</b>
 *
 * <ol>
 *   <li><b>The admin gate is enforced IN-CONTROLLER</b> (via
 *       {@link Authentication} authorities, the exact check
 *       {@code ApprovalController#isTenantAdmin} uses) instead of a
 *       {@code requestMatchers} line in {@code SecurityConfig}.
 *       {@code SecurityConfig} is dirty in the working tree under a
 *       concurrent workstream that owns it; this controller cannot add its
 *       line without a collision, and shipping the gate in-controller means
 *       the endpoints are protected REGARDLESS of when that line lands.
 *       All routes still require authentication (the chain's terminal
 *       {@code .anyRequest().authenticated()}). Follow-up flagged for the
 *       SecurityConfig owner: mirror the {@code /api/channels/pairing/**}
 *       block with {@code hasAuthority("ROLE_tenant-admin")} on the POSTs
 *       for defense in depth.</li>
 *   <li><b>403s are returned as explicit {@code ResponseEntity} bodies</b>
 *       ({@code {"error": ...}}, the shape the web client's {@code toJson}
 *       expects) rather than thrown as a typed exception: the central
 *       mapper ({@code GlobalExceptionHandler}, another scope) has no
 *       suitable 403 type, and its {@code Exception} catch-all turns
 *       unmapped throws into 500s + Issues-portal noise. Precedent for
 *       explicit status juggling in this package:
 *       {@code ChannelPairingController#eraseSubject}'s 404/204.</li>
 * </ol>
 *
 * <p><b>Read tiers:</b> the inbox LIST is open to any authenticated tenant
 * member - it exposes masked external ids and the same last-message previews
 * {@code ChannelStatusService#recentInbound} already shows every member.
 * The TRANSCRIPT is admin-only: full message bodies are customer content, a
 * strictly larger disclosure than a one-line preview, and the operators who
 * act on them are tenant-admins today (same tier {@code ApprovalsPanel}
 * already requires for its decision queue).
 *
 * <p>External identifiers are masked in every response (last 4 only), the
 * same convention {@code ChannelPairingController.PendingPairingResponse}
 * documents - an operator identifies a conversation by its preview and
 * timing, and replies by session id; the full phone number/chat id never
 * needs to cross the wire.
 */
@RestController
@RequestMapping("/api/handoff")
public class OperatorInboxController {

    // Granular RBAC (2026-09-23): operator actions track MANAGE_CHANNELS -
    // the human-handoff inbox is channel operations, same scope as the
    // channel/contact admin surfaces. platform-admin keeps the role check.
    private static final String OPERATOR_AUTHORITY = "PERM_MANAGE_CHANNELS";
    private static final String PLATFORM_ADMIN_AUTHORITY = "ROLE_platform-admin";

    private final OperatorInboxService inboxService;
    private final HandoffEscalationService escalationService;

    public OperatorInboxController(OperatorInboxService inboxService, HandoffEscalationService escalationService) {
        this.inboxService = inboxService;
        this.escalationService = escalationService;
    }

    /** The queue: every conversation in AWAITING_HUMAN or HUMAN, newest activity first. Open to any authenticated member (see class javadoc's read-tier note). */
    @GetMapping("/inbox")
    public ResponseEntity<List<InboxSessionResponse>> inbox(@RequestParam(required = false) Integer limit, @RequestParam(required = false) Long offset) {
        return ListPaging.window(
            inboxService.listNeedingAttention().stream().map(InboxSessionResponse::from).toList(),
            limit, offset, 50, 500);
    }

    /**
     * The full conversations table behind the rebuilt inbox page
     * (2026-09-23) - every channel session, paged and filterable, so the
     * operator sees incoming AND outgoing traffic across all ten channels
     * in one place rather than only the escalated slice {@link #inbox()}
     * serves. Same masked member read-tier as the inbox list - everything
     * here is identifiers truncated to last-4 plus the previews {@code
     * ChannelStatusService#recentInbound} already shows members.
     *
     * <p>{@code view} resolves to the (state, paired) pair each inbox tab
     * needs: {@code waiting} = AWAITING_HUMAN, {@code held} = HUMAN, {@code
     * agent} = paired AGENT conversations, {@code pending} = the
     * still-unpaired queue (any state), {@code all} = no constraint. {@code
     * q} substring-matches routing id / resolved phone / last preview (see
     * {@code ChannelSessionRepository#searchConversations}). Page size is
     * clamped to 100 - the same bound the transcript's top-200 cap sets.
     */
    @GetMapping("/conversations")
    public ConversationPageResponse conversations(
        @RequestParam(required = false) ChannelType channel,
        @RequestParam(defaultValue = "all") String view,
        @RequestParam(required = false) String q,
        @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "25") int size
    ) {
        HandoffState state = switch (view) {
            case "waiting" -> HandoffState.AWAITING_HUMAN;
            case "held" -> HandoffState.HUMAN;
            case "agent" -> HandoffState.AGENT;
            default -> null;
        };
        Boolean paired = switch (view) {
            case "agent" -> Boolean.TRUE;
            case "pending" -> Boolean.FALSE;
            default -> null;
        };
        String query = q == null || q.isBlank() ? null : q.trim();
        Page<ChannelSession> result = inboxService.searchConversations(
            channel, state, paired, query,
            PageRequest.of(Math.max(0, page), Math.min(Math.max(size, 1), 100))
        );
        return new ConversationPageResponse(
            result.getContent().stream().map(ConversationSummary::from).toList(),
            result.getTotalElements(), result.getNumber(), result.getTotalPages()
        );
    }

    /** The stats strip above the conversation list - waiting / held / pending-pairing / agent-handled / total. Same member read-tier as {@link #conversations}. */
    @GetMapping("/stats")
    public OperatorInboxService.InboxStats stats() {
        return inboxService.stats();
    }

    /**
     * One conversation's transcript, oldest-first, newest 200 rows.
     * Admin-only: full message bodies are customer content (class javadoc).
     */
    @GetMapping("/sessions/{sessionId}/transcript")
    public ResponseEntity<?> transcript(@PathVariable UUID sessionId, Authentication authentication) {
        if (!isTenantAdmin(authentication)) {
            return forbidden();
        }
        List<TranscriptMessageResponse> messages =
            inboxService.transcript(sessionId).stream().map(TranscriptMessageResponse::from).toList();
        return ResponseEntity.ok(messages);
    }

    /**
     * Manually flag a conversation as needing a person - the operator-side
     * twin of the designed {@code request_human} agent tool (which will call
     * {@code HandoffEscalationService#requestHumanForAgentSession} once its
     * owning workstream lands it). Admin-only: escalating fires a broadcast
     * notification at every tenant-admin, which a non-admin member should
     * not be able to spam.
     */
    @PostMapping("/sessions/{sessionId}/escalate")
    public ResponseEntity<?> escalate(
        @PathVariable UUID sessionId, @Valid @RequestBody EscalateRequest request, Authentication authentication
    ) {
        if (!isTenantAdmin(authentication)) {
            return forbidden();
        }
        return ResponseEntity.ok(InboxSessionResponse.from(
            escalationService.escalate(sessionId, request.reason(), callerSubject(authentication))));
    }

    /** Claim the conversation - the agent (and channel workflow trigger) go silent until release. Admin-only. */
    @PostMapping("/sessions/{sessionId}/take-over")
    public ResponseEntity<?> takeOver(@PathVariable UUID sessionId, Authentication authentication) {
        if (!isTenantAdmin(authentication)) {
            return forbidden();
        }
        return ResponseEntity.ok(InboxSessionResponse.from(
            inboxService.takeOver(sessionId, callerSubject(authentication))));
    }

    /** Send one operator-authored message through the channel's existing gated send chain. Admin-only; requires the conversation to be held (see {@code OperatorInboxService#reply}). */
    @PostMapping("/sessions/{sessionId}/reply")
    public ResponseEntity<?> reply(
        @PathVariable UUID sessionId, @Valid @RequestBody ReplyRequest request, Authentication authentication
    ) {
        if (!isTenantAdmin(authentication)) {
            return forbidden();
        }
        return ResponseEntity.ok(InboxSessionResponse.from(
            inboxService.reply(sessionId, request.body(), callerSubject(authentication))));
    }

    /** Hand the conversation back to the agent. Admin-only; idempotent. */
    @PostMapping("/sessions/{sessionId}/release")
    public ResponseEntity<?> release(@PathVariable UUID sessionId, Authentication authentication) {
        if (!isTenantAdmin(authentication)) {
            return forbidden();
        }
        return ResponseEntity.ok(InboxSessionResponse.from(
            inboxService.release(sessionId, callerSubject(authentication))));
    }

    public record EscalateRequest(@NotBlank String reason) {
    }

    public record ReplyRequest(@NotBlank String body) {
    }

    /**
     * {@code externalIdMasked}, never the full identifier - see the class
     * javadoc. {@code takenOverByMe} is computed client-side instead (the
     * client knows its own subject); the raw {@code takenOverBy} subject IS
     * returned because operators are tenant-admins who can already see each
     * other in the Users screen, and "who holds this" is the single most
     * important fact in the inbox.
     */
    public record InboxSessionResponse(
        UUID sessionId,
        ChannelType channel,
        String externalIdMasked,
        UUID agentId,
        HandoffState handoffState,
        String escalationReason,
        Instant escalatedAt,
        String takenOverBy,
        Instant takenOverAt,
        String lastMessagePreview,
        Instant lastMessageAt,
        Instant createdAt
    ) {
        static InboxSessionResponse from(ChannelSession session) {
            return new InboxSessionResponse(
                session.getId(),
                session.getChannel(),
                mask(session.getExternalId()),
                session.getAgentId(),
                session.getHandoffState(),
                session.getEscalationReason(),
                session.getEscalatedAt(),
                session.getTakenOverBy(),
                session.getTakenOverAt(),
                session.getLastMessagePreview(),
                session.getLastMessageAt(),
                session.getCreatedAt()
            );
        }
    }

    /**
     * One row of the paged conversations list - {@link
     * InboxSessionResponse}'s fields plus the pairing state the "Pending"
     * tab acts on. {@code pairingCode} is only populated while {@code
     * paired} is false (it is nulled on approval and re-minted on unpair),
     * matching {@code ChannelPairingController.PendingPairingResponse}'s
     * exposure; {@code resolvedPhoneMasked} gets the same last-4 masking as
     * the routing id.
     */
    public record ConversationSummary(
        UUID sessionId,
        ChannelType channel,
        String externalIdMasked,
        String resolvedPhoneMasked,
        boolean paired,
        String pairingCode,
        UUID agentId,
        HandoffState handoffState,
        String escalationReason,
        Instant escalatedAt,
        String takenOverBy,
        Instant takenOverAt,
        String lastMessagePreview,
        Instant lastMessageAt,
        Instant createdAt
    ) {
        static ConversationSummary from(ChannelSession session) {
            return new ConversationSummary(
                session.getId(),
                session.getChannel(),
                mask(session.getExternalId()),
                mask(session.getResolvedPhoneNumber()),
                session.isPaired(),
                session.isPaired() ? null : session.getPairingCode(),
                session.getAgentId(),
                session.getHandoffState(),
                session.getEscalationReason(),
                session.getEscalatedAt(),
                session.getTakenOverBy(),
                session.getTakenOverAt(),
                session.getLastMessagePreview(),
                session.getLastMessageAt(),
                session.getCreatedAt()
            );
        }
    }

    /** Paged wrapper - Spring's {@code Page} serializes a deep, unstable shape; the client only needs these four fields. */
    public record ConversationPageResponse(
        List<ConversationSummary> items, long total, int page, int totalPages
    ) {
    }

    public record TranscriptMessageResponse(
        UUID id,
        ChannelMessageDirection direction,
        String author,
        String body,
        Instant createdAt
    ) {
        static TranscriptMessageResponse from(ChannelMessageLog log) {
            return new TranscriptMessageResponse(
                log.getId(), log.getDirection(), log.getAuthor(), log.getBody(), log.getCreatedAt());
        }
    }

    private static boolean isTenantAdmin(Authentication authentication) {
        return authentication != null && authentication.getAuthorities().stream()
            .anyMatch(authority -> OPERATOR_AUTHORITY.equals(authority.getAuthority())
                || PLATFORM_ADMIN_AUTHORITY.equals(authority.getAuthority()));
    }

    /**
     * Same subject-resolution order as {@code ApprovalController#callerSubject}
     * (TenantContext first, {@code Authentication#getName()} fallback,
     * "unknown" last) - the audit rows this feeds must attribute the same
     * way the approval audit rows do.
     */
    private static String callerSubject(Authentication authentication) {
        String subject = TenantContext.current().map(TenantContext::getSubject).orElse(null);
        if (subject == null || subject.isBlank()) {
            subject = authentication != null ? authentication.getName() : null;
        }
        return subject == null || subject.isBlank() ? "unknown" : subject;
    }

    private static ResponseEntity<Map<String, Object>> forbidden() {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
            .body(Map.of("error", "Only a tenant-admin can act on a live conversation handoff."));
    }

    private static String mask(String value) {
        if (value == null || value.length() < 4) {
            return "***";
        }
        return "***" + value.substring(value.length() - 4);
    }
}
