package com.agenticai.core.omnichannel.session;

import org.springframework.web.bind.annotation.RequestParam;
import com.agenticai.core.tenancy.ListPaging;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Autonomy/Guardrails Strategy Stage A - a tenant-admin's view of every
 * inbound channel sender still awaiting pairing approval, plus the
 * approve/reject actions. Gated to {@code ROLE_tenant-admin} in
 * SecurityConfig, same posture as the other tenant-configuration writes
 * (MCP registry, approval policy, channel default-agent) - approving a
 * pairing grants a stranger a live conversation with this tenant's agents,
 * not a decision to leave to any authenticated user.
 */
@RestController
@RequestMapping("/api/channels/pairing")
public class ChannelPairingController {

    private final ChannelSessionService channelSessionService;

    public ChannelPairingController(ChannelSessionService channelSessionService) {
        this.channelSessionService = channelSessionService;
    }

    @GetMapping("/pending")
    public ResponseEntity<List<PendingPairingResponse>> pending(@RequestParam(required = false) Integer limit, @RequestParam(required = false) Long offset) {
        return ListPaging.window(
            channelSessionService.listPendingPairings().stream()
            .map(PendingPairingResponse::from)
            .toList(),
            limit, offset, 50, 500);
    }

    @PostMapping("/{sessionId}/approve")
    public PendingPairingResponse approve(@PathVariable UUID sessionId) {
        return PendingPairingResponse.from(channelSessionService.approvePairing(sessionId));
    }

    @DeleteMapping("/{sessionId}")
    public ResponseEntity<Void> reject(@PathVariable UUID sessionId) {
        channelSessionService.rejectPairing(sessionId);
        return ResponseEntity.status(HttpStatus.NO_CONTENT).build();
    }

    /**
     * "Unmap" an already-mapped conversation - returns it to the pending-pairing
     * inbox (fresh pairing code; sender re-challenged on their next message)
     * without deleting the session or its history. Same {@code ROLE_tenant-admin}
     * tier as approve/reject/rebind - unmapping ends a live conversation's trust
     * grant, the same consequence tier as granting it.
     */
    @PostMapping("/{sessionId}/unpair")
    public PendingPairingResponse unpair(@PathVariable UUID sessionId) {
        return PendingPairingResponse.from(channelSessionService.unpairSession(sessionId));
    }

    /**
     * #590 follow-up ("it is selecting wrong agent for telegram message
     * sending") - re-points one already-existing conversation to a
     * different agent. Distinct from {@code PUT .../default-agent} (which
     * only affects a channel's future first-contacts): this fixes a
     * specific chat that's stuck on a stale binding right now, without
     * waiting for a brand-new external identity to pick up a corrected
     * default. Same {@code ROLE_tenant-admin} gate as approve/reject above -
     * re-routing a live conversation to a different agent is the same trust
     * tier as approving a stranger into one in the first place.
     */
    @PutMapping("/{sessionId}/agent")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void rebindAgent(@PathVariable UUID sessionId, @Valid @RequestBody RebindAgentRequest request) {
        channelSessionService.rebindAgent(sessionId, request.agentId());
    }

    public record RebindAgentRequest(@NotNull UUID agentId) {
    }

    /**
     * #815 Governance follow-up (PII handling review) - GDPR Article 17
     * "right to erasure" for one data subject, distinct from the tenant-wide
     * erasure schema-per-tenant already provides structurally. See {@link
     * ChannelSessionService#eraseDataSubject}'s javadoc for exactly what
     * this does and does not delete. {@code ROLE_tenant-admin}-gated, same
     * tier as approve/reject/rebind above - erasing a real person's data is
     * at least as consequential as any of those.
     */
    @PostMapping("/erase-subject")
    public ResponseEntity<Void> eraseSubject(@Valid @RequestBody EraseSubjectRequest request) {
        boolean erased = channelSessionService.eraseDataSubject(request.channel(), request.externalId());
        return erased
            ? ResponseEntity.status(HttpStatus.NO_CONTENT).build()
            : ResponseEntity.status(HttpStatus.NOT_FOUND).build();
    }

    public record EraseSubjectRequest(@NotNull ChannelType channel, @NotNull String externalId) {
    }

    /**
     * {@code externalIdMasked} - never the full phone number/chat id, same
     * masking convention {@code TwilioWebhookController} already applies
     * when logging (last 4 characters only). An admin verifying a pairing
     * cross-references the pairing code the sender reports back, not the
     * full identifier shown here.
     */
    public record PendingPairingResponse(
        UUID sessionId,
        ChannelType channel,
        String externalIdMasked,
        String pairingCode,
        Instant createdAt
    ) {
        static PendingPairingResponse from(ChannelSession session) {
            return new PendingPairingResponse(
                session.getId(),
                session.getChannel(),
                mask(session.getExternalId()),
                session.getPairingCode(),
                session.getCreatedAt()
            );
        }

        private static String mask(String value) {
            if (value == null || value.length() < 4) {
                return "***";
            }
            return "***" + value.substring(value.length() - 4);
        }
    }
}
