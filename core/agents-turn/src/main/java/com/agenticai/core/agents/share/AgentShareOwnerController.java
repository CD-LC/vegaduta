package com.agenticai.core.agents.share;

import com.agenticai.core.sharing.ResourceAccessService;
import com.agenticai.core.sharing.ResourceType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The OWNER half of "even registered consumers should be able to share
 * their agents to external world" - mint/inspect/revoke a share link for one
 * of the caller's own agents. Authenticated normally (default {@code
 * anyRequest().authenticated()} in {@code SecurityConfig} - no admin gate,
 * matching the "all registered users, capped usage" decision this feature
 * shipped under).
 *
 * <p><b>Every endpoint here requires OWNERSHIP, not read access</b> -
 * {@link AgentShareService#requireOwnedAgent} 404s both for an agent id
 * outside the caller's own tenant schema (schema-per-tenant query scoping)
 * AND for an agent inside it that the caller neither owns nor is a
 * tenant-admin over. That is stricter than the rest of the agent API on
 * purpose: what these three endpoints control is exposure of the agent to
 * the anonymous internet, which is a strictly bigger grant than handing one
 * named colleague EDITOR - and {@code ResourceAccessService#grant} already
 * refuses to let an EDITOR-level grantee widen access further.
 *
 * <p><b>2026-07-29 - this used to be wrong.</b> All three endpoints called
 * {@code AgentService#get} under a comment claiming it "404s if this isn't
 * the caller's own agent". It does not: {@code AgentService#get} is a {@code
 * PermissionLevel.VIEWER} check, so a VIEWER-level grantee could publish a
 * colleague's agent to the world and revoke the owner's own link - and while
 * every agent row still had {@code created_by IS NULL} (all 38 of them, live,
 * on the day this was found), {@code requireAccess} short-circuited to
 * {@code LEGACY_OPEN} and the answer was "any authenticated tenant member on
 * any agent". Neither the mint nor the revoke wrote an audit row either,
 * unlike their grant/revoke siblings; both do now.
 *
 * <p>The PUBLIC half - what an anonymous viewer with the link can do - is
 * {@link AgentShareController}, under {@code /api/share/agents/{shareId}}.
 */
@RestController
@RequestMapping("/api/agents/{agentId}/share")
public class AgentShareOwnerController {

    private final AgentShareService shareService;
    private final ResourceAccessService resourceAccessService;

    public AgentShareOwnerController(AgentShareService shareService, ResourceAccessService resourceAccessService) {
        this.shareService = shareService;
        this.resourceAccessService = resourceAccessService;
    }

    /**
     * When {@code active} is false but a link once existed, {@code revokedReason} says why
     * ({@code ABUSE_REPORTS}, {@code OWNER_REVOKED} or {@code EXPIRED}), {@code reportCount} is the
     * number of abuse reports it received, and {@code revokedAt} is when it was revoked (null for
     * expiry, where {@code expiresAt} is the moment). With no link at all, the reason is null.
     *
     * <p>{@code expiresAt} (SEC-051): when the link stops resolving; null = legacy non-expiring link. */
    public record ShareStatusResponse(
        boolean active, String shareId, String shareUrl, long viewerCount, int reportCount, java.time.Instant expiresAt,
        String revokedReason, java.time.Instant revokedAt
    ) {
        static ShareStatusResponse inactive() {
            return new ShareStatusResponse(false, null, null, 0, 0, null, null, null);
        }

        static ShareStatusResponse live(String shareId, String shareUrl, long viewerCount, int reportCount,
                                          java.time.Instant expiresAt) {
            return new ShareStatusResponse(true, shareId, shareUrl, viewerCount, reportCount, expiresAt, null, null);
        }
    }

    /**
     * Read-only status - owner-or-admin, same as the two mutating endpoints
     * below. Not softened to VIEWER: this response carries the live share URL
     * itself, so anything short of ownership here would hand a non-owner the
     * public capability URL rather than merely telling them one exists.
     */
    @GetMapping
    public ShareStatusResponse status(@PathVariable UUID agentId) {
        shareService.requireOwnedAgent(agentId); // 404s unless the caller owns this agent (or is a tenant-admin).
        Optional<AgentShare> share = shareService.statusFor(agentId);
        // SEC-051: an expired link is reported inactive - and checked BEFORE createOrGet,
        // which would otherwise mint a replacement link as a side effect of a status read.
        if (share.isEmpty()) {
            return ShareStatusResponse.inactive();
        }
        if (!shareService.isLive(share.get())) {
            AgentShare dead = share.get();
            return new ShareStatusResponse(false, null, null, 0, dead.getReportCount(), dead.getExpiresAt(),
                shareService.endedReason(dead), dead.isRevoked() ? dead.getUpdatedAt() : null);
        }
        AgentShare s = share.get();
        AgentShareService.ShareLink link = shareService.createOrGet(agentId); // idempotent - just re-derives the URL.
        return ShareStatusResponse.live(s.getShareId(), link.shareUrl(), s.getViewerCount(), s.getReportCount(),
            s.getExpiresAt());
    }

    @PostMapping
    public ResponseEntity<ShareStatusResponse> create(@PathVariable UUID agentId) {
        shareService.requireOwnedAgent(agentId);
        AgentShareService.ShareLink link = shareService.createOrGet(agentId);
        AgentShare share = shareService.statusFor(agentId).orElseThrow();
        // Audited exactly like ResourceAccessService#grant audits handing a
        // single colleague access - this is the same class of act, aimed at
        // the whole internet instead. The share id is recorded (it is the
        // public, already-circulated half of the link, not a secret held back
        // from the audit trail) so a later revoke can be tied to what it
        // killed; the full URL is not, it adds nothing but the host.
        resourceAccessService.recordPublicLinkCreated(
            ResourceType.AGENT, agentId, "Minted public share link " + link.shareId()
        );
        return ResponseEntity.status(HttpStatus.CREATED)
            .body(ShareStatusResponse.live(link.shareId(), link.shareUrl(), share.getViewerCount(), share.getReportCount(),
                share.getExpiresAt()));
    }

    @DeleteMapping
    public ResponseEntity<Void> revoke(@PathVariable UUID agentId) {
        shareService.requireOwnedAgent(agentId);
        // Read the share id BEFORE revoking so the audit row names the link
        // that was killed. Only audited when there was a live share to kill -
        // same "audit an actual state change, not a no-op" rule
        // ResourceAccessService#revoke follows.
        Optional<AgentShare> existing = shareService.statusFor(agentId).filter(share -> !share.isRevoked());
        shareService.revoke(agentId);
        existing.ifPresent(share -> resourceAccessService.recordPublicLinkRevoked(
            ResourceType.AGENT, agentId, "Revoked public share link " + share.getShareId()
        ));
        return ResponseEntity.noContent().build();
    }
}
