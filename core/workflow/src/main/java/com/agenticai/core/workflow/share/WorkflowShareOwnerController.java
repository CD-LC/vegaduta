package com.agenticai.core.workflow.share;

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
 * The OWNER half of Workflow Share - mint/inspect/revoke a share link for one
 * of the caller's own workflows. Mirrors {@code
 * com.agenticai.core.agents.share.AgentShareOwnerController} exactly (see
 * that class's javadoc for the full reasoning, which applies identically
 * here with "workflow" in place of "agent") - authenticated normally, no
 * admin gate, and every endpoint requires OWNERSHIP (not read/run access):
 * {@link WorkflowShareService#requireOwnedWorkflow} 404s both for a workflow
 * id outside the caller's own tenant schema AND for one inside it that the
 * caller neither owns nor is a tenant-admin over.
 *
 * <p>The PUBLIC half - what an anonymous viewer with the link can do - is
 * {@link WorkflowShareController}, under {@code /api/share/workflows/{shareId}}.
 */
@RestController
@RequestMapping("/api/workflows/{workflowId}/share")
public class WorkflowShareOwnerController {

    private final WorkflowShareService shareService;
    private final ResourceAccessService resourceAccessService;

    public WorkflowShareOwnerController(WorkflowShareService shareService, ResourceAccessService resourceAccessService) {
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
     * below. Not softened to a lesser grant: this response carries the live
     * share URL itself.
     */
    @GetMapping
    public ShareStatusResponse status(@PathVariable UUID workflowId) {
        shareService.requireOwnedWorkflow(workflowId); // 404s unless the caller owns this workflow (or is a tenant-admin).
        Optional<WorkflowShare> share = shareService.statusFor(workflowId);
        // SEC-051: expired = inactive, checked BEFORE createOrGet (which would mint a replacement).
        if (share.isEmpty()) {
            return ShareStatusResponse.inactive();
        }
        if (!shareService.isLive(share.get())) {
            WorkflowShare dead = share.get();
            return new ShareStatusResponse(false, null, null, 0, dead.getReportCount(), dead.getExpiresAt(),
                shareService.endedReason(dead), dead.isRevoked() ? dead.getUpdatedAt() : null);
        }
        WorkflowShare s = share.get();
        WorkflowShareService.ShareLink link = shareService.createOrGet(workflowId); // idempotent - just re-derives the URL.
        return ShareStatusResponse.live(s.getShareId(), link.shareUrl(), s.getViewerCount(), s.getReportCount(),
            s.getExpiresAt());
    }

    @PostMapping
    public ResponseEntity<ShareStatusResponse> create(@PathVariable UUID workflowId) {
        shareService.requireOwnedWorkflow(workflowId);
        WorkflowShareService.ShareLink link = shareService.createOrGet(workflowId);
        WorkflowShare share = shareService.statusFor(workflowId).orElseThrow();
        // Audited exactly like AgentShareOwnerController#create - the share
        // id is recorded (the public, already-circulated half of the link,
        // not a secret) so a later revoke can be tied to what it killed.
        resourceAccessService.recordPublicLinkCreated(
            ResourceType.WORKFLOW, workflowId, "Minted public share link " + link.shareId()
        );
        return ResponseEntity.status(HttpStatus.CREATED)
            .body(ShareStatusResponse.live(link.shareId(), link.shareUrl(), share.getViewerCount(), share.getReportCount(),
                share.getExpiresAt()));
    }

    @DeleteMapping
    public ResponseEntity<Void> revoke(@PathVariable UUID workflowId) {
        shareService.requireOwnedWorkflow(workflowId);
        // Read the share id BEFORE revoking so the audit row names the link
        // that was killed. Only audited when there was a live share to kill.
        Optional<WorkflowShare> existing = shareService.statusFor(workflowId).filter(share -> !share.isRevoked());
        shareService.revoke(workflowId);
        existing.ifPresent(share -> resourceAccessService.recordPublicLinkRevoked(
            ResourceType.WORKFLOW, workflowId, "Revoked public share link " + share.getShareId()
        ));
        return ResponseEntity.noContent().build();
    }
}
