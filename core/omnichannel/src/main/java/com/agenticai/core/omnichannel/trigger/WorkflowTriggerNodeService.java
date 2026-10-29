package com.agenticai.core.omnichannel.trigger;

import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Deliberately "dumb" node-to-channel-binding CRUD, with NO validation of
 * whether the workflow id / node id actually exist or are part of an ACTIVE
 * workflow's current graph - this module has no dependency on {@code
 * core-workflow} and can't check that (see {@link WorkflowTriggerChannel}'s
 * javadoc). {@code WorkflowService} (in {@code core-workflow}, which already
 * depends on this module) does that validation BEFORE calling {@link
 * #setTrigger}/{@link #enableWebhook} here - by the time this class is
 * invoked, the caller has already confirmed the workflow is real, ACTIVE, and
 * that {@code nodeId} names an actual Trigger node in its current graph.
 * Read by the inbound channel controllers ({@code TwilioWebhookController},
 * {@code TwilioSmsWebhookController}, etc.) before they fall back to their
 * existing default-agent-chat behavior.
 *
 * <p>Replaces {@code ChannelWorkflowTriggerService}, which modeled "one
 * trigger channel per WHOLE WORKFLOW" (one row per channel, pointing at a
 * workflow id). This class instead models "each Trigger NODE in a workflow's
 * graph can be independently bound to its own inbound channel" - every
 * lookup and mutation is keyed by {@code nodeId}, not {@code workflowId}, and
 * a binding now resolves to a {@link TriggerBinding} (workflow id + node id)
 * rather than a bare workflow id, since a downstream workflow run needs to
 * know WHICH node to start from.
 *
 * <p><b>Exception-handling note for the {@code core-workflow} caller:</b>
 * {@link #setTrigger} and {@link #enableWebhook} let an {@link
 * IllegalStateException} propagate when the partial unique index on {@code
 * channel} (one binding per non-WEBHOOK channel, tenant-wide - see the
 * migration) rejects the write. This module cannot throw {@code
 * core-workflow}'s {@code WorkflowValidationException} itself without
 * creating the same module-dependency cycle {@link WorkflowTriggerChannel}'s
 * javadoc explains {@link WorkflowTriggerPort} exists to avoid - so {@code
 * WorkflowService} (the only caller that mutates trigger bindings) is
 * expected to catch {@code IllegalStateException} from these two methods and
 * translate it into its own {@code WorkflowValidationException}.
 */
@Service
public class WorkflowTriggerNodeService {

    private static final Logger log = LoggerFactory.getLogger(WorkflowTriggerNodeService.class);

    private final WorkflowTriggerNodeRepository repository;
    private final WorkflowTriggerRateLimiter rateLimiter;

    public WorkflowTriggerNodeService(WorkflowTriggerNodeRepository repository, WorkflowTriggerRateLimiter rateLimiter) {
        this.repository = repository;
        this.rateLimiter = rateLimiter;
    }

    /**
     * Which workflow, and which node within it, is bound to inbound messages
     * on {@code channel} for the currently-active tenant - replaces {@code
     * ChannelWorkflowTriggerService#workflowIdFor}, now also returning the
     * node id a triggered run must start from.
     */
    @Transactional(readOnly = true)
    public Optional<TriggerBinding> bindingFor(WorkflowTriggerChannel channel) {
        return repository.findByChannel(channel).map(node -> new TriggerBinding(node.getWorkflowId(), node.getNodeId()));
    }

    /**
     * Direct feedback: "if I approve so many whatsapp message, then how do
     * i control? every message might trigger workflow isnt it?" - unchanged
     * from {@code ChannelWorkflowTriggerService}: true means go ahead and
     * call {@code workflowTriggerPort.triggerAsync(...)}; false means this
     * sender has exhausted their trigger budget for the current window and
     * this message should be silently skipped (not an error, not a fallback
     * to default-agent-chat - just "no workflow run for this one"). Kept as
     * a separate check rather than folded into {@link #bindingFor} so the
     * "is a workflow bound at all" question and the "is this specific
     * sender still within budget" question stay independently answerable
     * (callers already log/branch on the former separately).
     */
    public boolean tryAcquireTriggerSlot(WorkflowTriggerChannel channel, String externalId) {
        return rateLimiter.tryAcquire(channel, externalId);
    }

    @Transactional(readOnly = true)
    public Optional<WorkflowTriggerNode> getTrigger(WorkflowTriggerChannel channel) {
        return repository.findByChannel(channel);
    }

    /**
     * Upsert keyed by {@code nodeId} (a trigger node keeps a stable id for
     * its lifetime and can only ever be bound to one channel, so {@code
     * nodeId} - not {@code channel} - is the natural key here, unlike the old
     * per-channel upsert this replaces). Throws {@link IllegalStateException}
     * - see class javadoc - if {@code channel} is already bound to a
     * DIFFERENT node (the partial unique index on {@code channel} for every
     * value except WEBHOOK).
     */
    @Transactional
    public void setTrigger(UUID workflowId, String nodeId, WorkflowTriggerChannel channel) {
        Optional<WorkflowTriggerNode> existing = repository.findByNodeId(nodeId);
        WorkflowTriggerNode entry = existing.orElseGet(() -> new WorkflowTriggerNode(workflowId, nodeId, channel));
        if (existing.isPresent() && (!workflowId.equals(entry.getWorkflowId()) || channel != entry.getChannel())) {
            log.info("Re-binding trigger node {} from (workflow {}, channel {}) to (workflow {}, channel {})",
                nodeId, entry.getWorkflowId(), entry.getChannel(), workflowId, channel);
        }
        entry.rebind(workflowId, channel);
        save(entry, channel);
    }

    @Transactional
    public void clearTrigger(String nodeId) {
        repository.findByNodeId(nodeId).ifPresent(repository::delete);
    }

    /**
     * Same upsert-by-{@code nodeId} shape as {@link #setTrigger}, but for a
     * WEBHOOK-channel trigger node plus its token material. {@code
     * encryptedToken}/{@code tokenHash} are expected to already be
     * AES-256-GCM ciphertext / a SHA-256 hex digest respectively - this
     * module does no encryption itself (no dependency on {@code
     * core-security}'s {@code EncryptionService}), the caller (@code
     * WorkflowService}) is responsible for minting/encrypting the token
     * before calling this.
     */
    @Transactional
    public void enableWebhook(UUID workflowId, String nodeId, String encryptedToken, String tokenHash) {
        WorkflowTriggerNode entry = repository.findByNodeId(nodeId)
            .orElseGet(() -> new WorkflowTriggerNode(workflowId, nodeId, WorkflowTriggerChannel.WEBHOOK));
        entry.rebind(workflowId, WorkflowTriggerChannel.WEBHOOK);
        entry.enableWebhook(encryptedToken, tokenHash);
        save(entry, WorkflowTriggerChannel.WEBHOOK);
    }

    /**
     * Keeps the token on the row - matches today's semantics of "stop
     * responding, but don't forget the token", so re-enabling later doesn't
     * need to mint a new one.
     */
    @Transactional
    public void disableWebhook(String nodeId) {
        repository.findByNodeId(nodeId).ifPresent(entry -> {
            entry.disableWebhook();
            repository.save(entry);
        });
    }

    @Transactional(readOnly = true)
    public Optional<WorkflowTriggerNode> findByWebhookTokenHash(String hash) {
        return repository.findByWebhookTokenHash(hash);
    }

    /**
     * Added by the core-workflow-owning agent while wiring {@code
     * WorkflowService} against this class (per-node workflow triggers task):
     * this class's original six methods have no way to answer "what is
     * trigger node X's CURRENT state" - {@code WorkflowService#getWebhook}
     * (GET .../nodes/{nodeId}/webhook) and {@code WorkflowService#enableWebhook}'s
     * "reuse the already-minted token across disable/re-enable" behavior both
     * need exactly this lookup, and there was no other way to get it from the
     * given method set. Thin delegate over {@link
     * WorkflowTriggerNodeRepository#findByNodeId}, which already existed -
     * purely additive, does not change any existing method's behavior. If
     * this collides with separate in-flight work on this file, treat this one
     * method (and {@link #setWebhookAuthMode}) as the two pieces of missing
     * surface to reconcile, not the surrounding diff mechanics.
     */
    @Transactional(readOnly = true)
    public Optional<WorkflowTriggerNode> findByNodeId(String nodeId) {
        return repository.findByNodeId(nodeId);
    }

    /**
     * Every trigger-node row bound to a workflow. {@code
     * WorkflowWebhookController}'s OIDC endpoint needs this to confirm the
     * target workflow actually has a WEBHOOK-channel trigger node configured
     * for OAUTH2 before running it (a coarse tenant-wide {@code
     * workflow-trigger} role must not unlock every ACTIVE workflow).
     */
    @Transactional(readOnly = true)
    public java.util.List<WorkflowTriggerNode> triggerNodesFor(UUID workflowId) {
        return repository.findByWorkflowId(workflowId);
    }

    /**
     * Added alongside {@link #findByNodeId} for the same reason (see that
     * method's javadoc) - {@code WorkflowService#setTriggerChannel}'s WEBHOOK
     * branch needs to persist a node's auth mode (SHARED_SECRET/OAUTH2)
     * independently of enabling/disabling the webhook itself (the {@code
     * WorkflowTriggerNode} row may not exist yet if the webhook was never
     * enabled, hence the upsert here, mirroring {@link #setTrigger}'s own
     * upsert-by-{@code nodeId} shape). {@code authMode} is a plain {@code
     * String} (not {@code core-workflow}'s {@code WebhookAuthMode} enum) for
     * the same module-dependency-direction reason documented on {@link
     * WorkflowTriggerNode#getWebhookAuthMode()}'s field.
     */
    @Transactional
    public void setWebhookAuthMode(UUID workflowId, String nodeId, String authMode) {
        WorkflowTriggerNode entry = repository.findByNodeId(nodeId)
            .orElseGet(() -> new WorkflowTriggerNode(workflowId, nodeId, WorkflowTriggerChannel.WEBHOOK));
        entry.setWebhookAuthMode(authMode);
        repository.save(entry);
    }

    /**
     * Persists a WEBHOOK trigger node's signature-verification config (V482)
     * with the same upsert-by-{@code nodeId} shape as {@link
     * #setWebhookAuthMode}. {@code secretEncrypted} is expected to already be
     * AES-256-GCM ciphertext (or null when {@code mode} is {@code "NONE"}) -
     * this module does no encryption itself.
     */
    @Transactional
    public void setWebhookSignature(UUID workflowId, String nodeId, String mode, String secretEncrypted) {
        WorkflowTriggerNode entry = repository.findByNodeId(nodeId)
            .orElseGet(() -> new WorkflowTriggerNode(workflowId, nodeId, WorkflowTriggerChannel.WEBHOOK));
        entry.setWebhookSignature(mode, secretEncrypted);
        repository.save(entry);
    }

    private void save(WorkflowTriggerNode entry, WorkflowTriggerChannel channel) {
        try {
            repository.save(entry);
            repository.flush();
        } catch (DataIntegrityViolationException e) {
            throw new IllegalStateException("Channel " + channel + " is already bound to another trigger node.", e);
        }
    }

    /**
     * A trigger binding's resolution: which workflow to run, and which node
     * in its graph to start the run from. Replaces the bare {@code
     * Optional<UUID>} {@code ChannelWorkflowTriggerService#workflowIdFor}
     * used to return - a per-node binding must also tell the caller WHERE in
     * the graph to start.
     */
    public record TriggerBinding(UUID workflowId, String nodeId) {
    }
}
