package com.agenticai.core.workflow;

import com.agenticai.core.crypto.EncryptionService;
import com.agenticai.core.omnichannel.trigger.WorkflowTriggerChannel;
import com.agenticai.core.omnichannel.trigger.WorkflowTriggerNode;
import com.agenticai.core.omnichannel.trigger.WorkflowTriggerNodeService;
import com.agenticai.core.security.TenantContext;
import com.agenticai.core.sharing.PermissionLevel;
import com.agenticai.core.tenancy.ExternalAccountProvider;
import com.agenticai.core.tenancy.TenantResolutionService;
import com.agenticai.core.tenancy.TenantSchemaResolver;
import com.agenticai.core.workflow.dto.WebhookSignatureRequest;
import com.agenticai.core.workflow.dto.WebhookSignatureResponse;
import com.agenticai.core.workflow.dto.WorkflowGraphDto;
import com.agenticai.core.workflow.dto.WorkflowNodeDto;
import com.agenticai.core.workflow.dto.WorkflowPollTriggerRequest;
import com.agenticai.core.workflow.dto.WorkflowPollTriggerResponse;
import com.agenticai.core.workflow.dto.WorkflowTriggerChannelResponse;
import com.agenticai.core.workflow.dto.WorkflowWebhookResponse;
import com.agenticai.core.workflow.poll.WorkflowPollTrigger;
import com.agenticai.core.workflow.poll.WorkflowPollTriggerService;
import java.security.SecureRandom;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * All per-node trigger configuration for a workflow: webhook token +
 * auth-mode + signature verification, inbound-channel binding, and the HTTP
 * poll trigger. Extracted from {@link WorkflowService} (file-size ratchet -
 * that class was already over the 1000-line cap) as one cohesive unit: every
 * method here answers "how does this Trigger node fire".
 *
 * <p>The access/editable/automation gates stay on {@link WorkflowService}
 * (they're its domain: workflow ownership, plan limits, graph state) and are
 * package-private for exactly this class to call - same-module delegation,
 * not a second authority. {@code WorkflowService} keeps public delegates of
 * every method here so existing callers/tests keep compiling unchanged.
 */
@Service
public class WorkflowTriggerConfigService {

    private final WorkflowService workflowService;
    private final WorkflowTriggerNodeService workflowTriggerNodeService;
    private final EncryptionService encryptionService;
    private final TenantResolutionService tenantResolutionService;
    private final TenantSchemaResolver tenantSchemaResolver;
    private final WorkflowPollTriggerService pollTriggerService;
    private final SecureRandom secureRandom = new SecureRandom();

    public WorkflowTriggerConfigService(
        WorkflowService workflowService,
        WorkflowTriggerNodeService workflowTriggerNodeService,
        EncryptionService encryptionService,
        TenantResolutionService tenantResolutionService,
        TenantSchemaResolver tenantSchemaResolver,
        WorkflowPollTriggerService pollTriggerService
    ) {
        this.workflowService = workflowService;
        this.workflowTriggerNodeService = workflowTriggerNodeService;
        this.encryptionService = encryptionService;
        this.tenantResolutionService = tenantResolutionService;
        this.tenantSchemaResolver = tenantSchemaResolver;
        this.pollTriggerService = pollTriggerService;
    }

    /**
     * Per-node workflow triggers - {@code nodeId} is now required: the webhook
     * token/enabled-flag/auth-mode that used to live directly on {@link WorkflowDefinition} now
     * belongs to a {@code WorkflowTriggerNode} row (owned by {@code core-omnichannel}'s {@link
     * WorkflowTriggerNodeService} - see that interface's javadoc), one per Trigger node in the
     * graph, so every trigger node can mint its own independent webhook URL. Requires the workflow
     * to already be {@link WorkflowStatus#ACTIVE} - enabling a trigger for a graph that hasn't
     * passed governance validation would let a public URL run something that was never actually
     * checked, defeating the point of {@link OrchestratorService#validate} - and that {@code
     * nodeId} actually names a Trigger node in the workflow's CURRENT graph (a node id from a stale
     * client, or one since deleted/retyped by an edit, must not silently mint a token for a node
     * that no longer exists or isn't a trigger).
     */
    @Transactional
    public WorkflowWebhookResponse enableWebhook(UUID id, String nodeId) {
        WorkflowDefinition definition = workflowService.requireAccess(id, PermissionLevel.EDITOR);
        workflowService.assertEditable(definition);
        workflowService.requireUnattendedAutomation();
        // 2026-08-09: requireTriggerNode BEFORE the ACTIVE gate (was reversed) -
        // same reasoning as setTriggerChannel's identical ordering: a stale/
        // deleted nodeId on a DRAFT workflow should get "not a Trigger node in
        // this workflow's current graph", not the misleading "Activate this
        // workflow..." when the real problem is the node id itself.
        requireTriggerNode(definition, nodeId);
        if (definition.getStatus() != WorkflowStatus.ACTIVE) {
            throw new WorkflowValidationException(
                    List.of("Activate this workflow before enabling its webhook trigger."));
        }
        // #815 Governance follow-up (roadmap §10.3 item 1) - the stored ciphertext is not the
        // plaintext token itself (see the old WorkflowDefinition.webhookTokenEncrypted javadoc,
        // now WorkflowTriggerNode's), so reusing an already-minted token (rather than silently
        // reminting - "kept stable across disable/re-enable" is still the contract) requires
        // decrypting it back first.
        Optional<WorkflowTriggerNode> existing = workflowTriggerNodeService.findByNodeId(nodeId);
        String token =
                existing.map(WorkflowTriggerNode::getWebhookTokenEncrypted)
                        .map(encryptionService::decrypt)
                        .orElseGet(this::generateToken);
        workflowTriggerNodeService.enableWebhook(
                id, nodeId, encryptionService.encrypt(token), WebhookTokenHasher.sha256Hex(token));
        // Cross-tenant lookup table (see TenantResolutionService's javadoc) -
        // registered every time enable is called (not just when the token is
        // first minted) so this is naturally idempotent/self-healing if the
        // link ever went missing.
        //
        // The externalAccountId stored here is the SHA-256 hash of the token, NOT the plaintext:
        // ChannelAccountLink lives in control_plane unencrypted, so storing the raw token would
        // leak it. The inbound resolve side (WorkflowWebhookController#trigger) hashes the caller's
        // raw path token with the same WebhookTokenHasher before calling resolveTenantSchema, so
        // the round-trip stays symmetric. This lookup key is only an opaque identity match (it
        // never
        // needs to be reversed back to the token - the plaintext is recovered from the encrypted
        // WorkflowTriggerNode column instead), so a one-way hash is the right primitive here.
        tenantResolutionService.link(
                ExternalAccountProvider.WORKFLOW_WEBHOOK,
                WebhookTokenHasher.sha256Hex(token),
                tenantSchemaResolver.resolveCurrentTenantIdentifier());
        return WorkflowWebhookResponse.of(token, true);
    }

    @Transactional
    public WorkflowWebhookResponse disableWebhook(UUID id, String nodeId) {
        WorkflowDefinition definition = workflowService.requireAccess(id, PermissionLevel.EDITOR);
        workflowService.assertEditable(definition);
        requireTriggerNode(definition, nodeId);
        workflowTriggerNodeService.disableWebhook(nodeId);
        return WorkflowWebhookResponse.of(decryptWebhookToken(nodeId), false);
    }

    /**
     * Security audit 2026-07-29 (F2) - this returns the workflow's PLAINTEXT webhook trigger token.
     * Its real gate is the {@code ROLE_tenant-admin} matcher {@code SecurityConfig} now carries for
     * {@code GET /api/workflows/&#42;/nodes/&#42;/webhook}, NOT the VIEWER check below: for any
     * pre-V151 workflow ({@code created_by} null) VIEWER collapses to "any tenant member" - see
     * {@link WorkflowService#requireAccess}'s javadoc. The VIEWER check is kept as the second layer
     * that still scopes a NON-legacy workflow to people who can actually see it. Neither layer is
     * redundant; do not remove either.
     */
    @Transactional(readOnly = true)
    public WorkflowWebhookResponse getWebhook(UUID id, String nodeId) {
        WorkflowDefinition definition = workflowService.requireAccess(id, PermissionLevel.VIEWER);
        requireTriggerNode(definition, nodeId);
        boolean enabled =
                workflowTriggerNodeService
                        .findByNodeId(nodeId)
                        .map(WorkflowTriggerNode::isWebhookEnabled)
                        .orElse(false);
        return WorkflowWebhookResponse.of(decryptWebhookToken(nodeId), enabled);
    }

    /**
     * Null-safe decrypt - a trigger node whose webhook was never enabled has no ciphertext to
     * decrypt at all.
     */
    private String decryptWebhookToken(String nodeId) {
        return workflowTriggerNodeService
                .findByNodeId(nodeId)
                .map(WorkflowTriggerNode::getWebhookTokenEncrypted)
                .map(encryptionService::decrypt)
                .orElse(null);
    }

    /**
     * Validates that {@code nodeId} names an actual {@code trigger}-type node in this workflow's
     * CURRENT saved graph - every per-node trigger-config mutation ({@link #setTriggerChannel},
     * {@link #enableWebhook}, {@link #disableWebhook}) must pass this before touching {@link
     * WorkflowTriggerNodeService}, since that service (deliberately "dumb", see its javadoc) has no
     * way to check this itself.
     */
    private WorkflowNodeDto requireTriggerNode(WorkflowDefinition definition, String nodeId) {
        WorkflowGraphDto graph = workflowService.readGraph(definition.getGraphJson());
        return graph.nodes().stream()
                .filter(
                        n ->
                                nodeId != null
                                        && nodeId.equals(n.id())
                                        && "trigger".equalsIgnoreCase(n.type()))
                .findFirst()
                .orElseThrow(
                        () ->
                                new WorkflowValidationException(
                                        List.of(
                                                "'"
                                                        + nodeId
                                                        + "' is not a Trigger node in this"
                                                        + " workflow's current graph.")));
    }

    /**
     * Webhook signature verification (V482): a second factor on the
     * shared-secret webhook URL - the token still resolves the node, but the
     * sender must additionally prove the per-node HMAC secret over the raw
     * request body ({@link WebhookSignatureMode}: GITHUB/STRIPE/SLACK presets
     * or a generic sha256 hex header). The secret is stored encrypted and
     * never returned; {@code mode NONE} clears it. Not gated by
     * requireUnattendedAutomation - unlike schedule/channel/webhook-enable,
     * this does not ARM unattended firing, it only hardens (or relaxes) a
     * trigger that already exists; EDITOR + the security matcher are the
     * right gate, same tier as the webhook endpoints it hangs off.
     */
    @Transactional
    public WebhookSignatureResponse setWebhookSignature(UUID id, String nodeId, WebhookSignatureRequest request) {
        WorkflowDefinition definition = workflowService.requireAccess(id, PermissionLevel.EDITOR);
        workflowService.assertEditable(definition);
        requireTriggerNode(definition, nodeId);
        WebhookSignatureMode mode = request.mode() == null ? WebhookSignatureMode.NONE : request.mode();
        String encryptedSecret = null;
        if (mode != WebhookSignatureMode.NONE) {
            String secret = request.secret();
            if (secret == null || secret.strip().length() < 8) {
                throw new WorkflowValidationException(
                        List.of("A signature secret of at least 8 characters is required when verification is on."));
            }
            encryptedSecret = encryptionService.encrypt(secret.strip());
        }
        workflowTriggerNodeService.setWebhookSignature(id, nodeId, mode.name(), encryptedSecret);
        return new WebhookSignatureResponse(nodeId, mode, mode != WebhookSignatureMode.NONE);
    }

    /**
     * Read-open like {@code GET .../trigger-channel}: returns mode and whether a
     * secret is stored, never the secret.
     */
    @Transactional(readOnly = true)
    public WebhookSignatureResponse getWebhookSignature(UUID id, String nodeId) {
        WorkflowDefinition definition = workflowService.requireAccess(id, PermissionLevel.VIEWER);
        requireTriggerNode(definition, nodeId);
        return workflowTriggerNodeService
                .findByNodeId(nodeId)
                .map(node -> {
                    WebhookSignatureMode mode =
                            WebhookSignatureMode.fromNullableName(node.getWebhookSignatureMode());
                    boolean configured = mode != WebhookSignatureMode.NONE
                            && node.getWebhookSignatureSecretEncrypted() != null;
                    return new WebhookSignatureResponse(nodeId, mode, configured);
                })
                .orElse(new WebhookSignatureResponse(nodeId, WebhookSignatureMode.NONE, false));
    }

    /**
     * HTTP polling trigger (V482) - "when a new item appears at this endpoint,
     * run the workflow". Same governance gates as the schedule trigger:
     * unattended, forever, spending against the configuring human's authority,
     * and the workflow must already be ACTIVE (an invalid graph must not be
     * reachable through a trigger). URL/interval/paths/header validation lives
     * in {@link WorkflowPollTriggerService#configure}.
     */
    @Transactional
    public WorkflowPollTriggerResponse configurePollTrigger(UUID id, String nodeId, WorkflowPollTriggerRequest request) {
        WorkflowDefinition definition = workflowService.requireAccess(id, PermissionLevel.EDITOR);
        workflowService.assertEditable(definition);
        workflowService.requireUnattendedAutomation();
        requireTriggerNode(definition, nodeId);
        if (definition.getStatus() != WorkflowStatus.ACTIVE) {
            throw new WorkflowValidationException(
                    List.of("Activate this workflow before enabling its poll trigger."));
        }
        WorkflowPollTrigger trigger = pollTriggerService.configure(
                id,
                nodeId,
                new WorkflowPollTriggerService.ConfigureRequest(
                        request.url(),
                        request.method(),
                        request.headers(),
                        request.intervalSeconds(),
                        request.itemsPath(),
                        request.idPath(),
                        request.seedOnEnable()));
        return pollTriggerResponse(trigger);
    }

    @Transactional
    public WorkflowPollTriggerResponse disablePollTrigger(UUID id, String nodeId) {
        WorkflowDefinition definition = workflowService.requireAccess(id, PermissionLevel.EDITOR);
        workflowService.assertEditable(definition);
        requireTriggerNode(definition, nodeId);
        pollTriggerService.disable(nodeId);
        return WorkflowPollTriggerResponse.unconfigured(nodeId);
    }

    /** Read-open like the schedule GET: configuration and status, no headers. */
    @Transactional(readOnly = true)
    public WorkflowPollTriggerResponse getPollTrigger(UUID id, String nodeId) {
        WorkflowDefinition definition = workflowService.requireAccess(id, PermissionLevel.VIEWER);
        requireTriggerNode(definition, nodeId);
        return pollTriggerService
                .get(nodeId)
                .map(this::pollTriggerResponse)
                .orElse(WorkflowPollTriggerResponse.unconfigured(nodeId));
    }

    private WorkflowPollTriggerResponse pollTriggerResponse(WorkflowPollTrigger t) {
        return new WorkflowPollTriggerResponse(
                t.getNodeId(),
                true,
                t.getUrl(),
                t.getMethod(),
                t.getIntervalSeconds(),
                t.getItemsPath(),
                t.getIdPath(),
                t.isSeedOnEnable(),
                t.isEnabled(),
                t.getHeadersEncrypted() != null,
                t.getNextPollAt(),
                t.getLastPolledAt(),
                t.getLastStatus(),
                t.getConsecutiveFailures());
    }

    /**
     * Per-node workflow triggers (each trigger node in a workflow's graph can be independently
     * bound to its own inbound channel) - replaces the old one-binding-per-WORKFLOW model. WEBHOOK
     * only ever updates the node's auth mode here; the token/enabled flag stay whatever {@link
     * #enableWebhook}/{@link #disableWebhook} last left them (unlike every other channel, WEBHOOK's
     * actual "on/off" switch is a separate action, same split as before). WHATSAPP/SMS/etc. require
     * the workflow to already be ACTIVE (same governance gate {@link #enableWebhook}/{@link
     * WorkflowService#enableSchedule} apply - a channel binding that can never have passed {@link
     * OrchestratorService#validate} shouldn't be reachable either), then claim that channel for
     * this NODE via {@link WorkflowTriggerNodeService#setTrigger} (a "dumb" CRUD in the omnichannel
     * module with no knowledge of workflow status - see that class's javadoc for why the validation
     * has to happen here instead), translating its {@code IllegalStateException} (channel already
     * claimed by a different node) into the same {@link WorkflowValidationException} shape every
     * other governance failure in this class uses. CALL is now backed by {@code
     * TwilioVoiceWebhookController} (inbound Twilio Voice webhook -&#62; speech-to-text via Twilio's
     * own {@code <Gather input="speech">} -&#62; {@code WorkflowTriggerPort#triggerSync} -&#62; the
     * run's output spoken back via TwiML {@code <Say>}) - see that controller's javadoc for the
     * full flow.
     */
    @Transactional
    public WorkflowTriggerChannelResponse setTriggerChannel(
            UUID id, String nodeId, WorkflowTriggerChannel channel, WebhookAuthMode authMode) {
        WorkflowDefinition definition = workflowService.requireAccess(id, PermissionLevel.EDITOR);
        workflowService.assertEditable(definition);
        workflowService.requireUnattendedAutomation();
        requireTriggerNode(definition, nodeId);
        if (channel == WorkflowTriggerChannel.WEBHOOK) {
            workflowTriggerNodeService.setWebhookAuthMode(
                    id,
                    nodeId,
                    (authMode != null ? authMode : WebhookAuthMode.SHARED_SECRET).name());
        } else {
            if (definition.getStatus() != WorkflowStatus.ACTIVE) {
                throw new WorkflowValidationException(
                        List.of(
                                "Activate this workflow before binding it to an inbound "
                                        + channel
                                        + " trigger."));
            }
            try {
                workflowTriggerNodeService.setTrigger(id, nodeId, channel);
            } catch (IllegalStateException e) {
                throw new WorkflowValidationException(
                        List.of("This channel is already bound to another trigger node."));
            }
        }
        // Direct feedback (2026-08-04): "values doesn't get updated, whenever
        // i click on same trigger node it says webhook - it should reflect
        // what[ever] trigger node is configured." Root cause:
        // triggerChannelResponse's own javadoc names the node's graph_json
        // config.channel as this read path's source of truth, but until now
        // nothing on THIS write path ever set it - only
        // WorkflowTriggerNodeService's separate table got updated, so a
        // fresh GET (reopening the node) always re-derived WEBHOOK from the
        // graph's still-absent channel key, even immediately after a
        // successful PUT. Persist the choice into the node's own config too,
        // in the same transaction, so the two stay in sync without requiring
        // a separate whole-workflow Save/Activate round trip.
        definition.update(
                definition.getName(),
                definition.getDescription(),
                workflowService.writeGraph(withNodeChannel(workflowService.readGraph(definition.getGraphJson()), nodeId, channel)),
                definition.getStatus());
        return triggerChannelResponse(definition, nodeId);
    }

    /**
     * Returns a copy of {@code graph} with trigger node {@code nodeId}'s {@code
     * data.config.channel} set to {@code channel.name()}.
     */
    private static WorkflowGraphDto withNodeChannel(
            WorkflowGraphDto graph, String nodeId, WorkflowTriggerChannel channel) {
        List<WorkflowNodeDto> nodes =
                graph.nodes().stream()
                        .map(
                                n -> {
                                    if (!nodeId.equals(n.id())) {
                                        return n;
                                    }
                                    Map<String, Object> newConfig = new HashMap<>(n.config());
                                    newConfig.put("channel", channel.name());
                                    Map<String, Object> newData =
                                            new HashMap<>(n.data() != null ? n.data() : Map.of());
                                    newData.put("config", newConfig);
                                    return new WorkflowNodeDto(
                                            n.id(), n.type(), n.position(), newData);
                                })
                        .toList();
        return new WorkflowGraphDto(nodes, graph.edges());
    }

    @Transactional(readOnly = true)
    public WorkflowTriggerChannelResponse getTriggerChannel(UUID id, String nodeId) {
        WorkflowDefinition definition = workflowService.requireAccess(id, PermissionLevel.VIEWER);
        requireTriggerNode(definition, nodeId);
        return triggerChannelResponse(definition, nodeId);
    }

    /**
     * The node's own {@code channel} config (validated by {@link OrchestratorService#validate}, so
     * it is trusted to be present and parseable by the time this is reached from a mutating call -
     * the read-only {@link #getTriggerChannel} path can still see a node saved before that
     * validation rule existed, so this degrades to WEBHOOK rather than throwing) is the source of
     * truth for WHICH channel this node is bound to; {@link WorkflowTriggerNodeService} only tracks
     * the webhook-specific token/enabled/auth-mode state and the non-WEBHOOK channel-uniqueness
     * claim, not the channel value itself.
     */
    private WorkflowTriggerChannelResponse triggerChannelResponse(
            WorkflowDefinition definition, String nodeId) {
        WorkflowNodeDto triggerNode = requireTriggerNode(definition, nodeId);
        Object channelRaw = triggerNode.config().get("channel");
        WorkflowTriggerChannel channel;
        try {
            channel =
                    channelRaw != null
                            ? WorkflowTriggerChannel.valueOf(
                                    channelRaw.toString().toUpperCase(java.util.Locale.ROOT))
                            : WorkflowTriggerChannel.WEBHOOK;
        } catch (IllegalArgumentException e) {
            channel = WorkflowTriggerChannel.WEBHOOK;
        }
        WebhookAuthMode authMode =
                workflowTriggerNodeService
                        .findByNodeId(nodeId)
                        .map(node -> WebhookAuthMode.fromNullableName(node.getWebhookAuthMode()))
                        .orElse(WebhookAuthMode.SHARED_SECRET);
        String oidcPath =
                channel == WorkflowTriggerChannel.WEBHOOK && authMode == WebhookAuthMode.OAUTH2
                        ? "/webhook/workflows/oidc/" + definition.getId()
                        : null;
        return new WorkflowTriggerChannelResponse(nodeId, channel, authMode, oidcPath);
    }

    private String generateToken() {
        byte[] bytes = new byte[24];
        secureRandom.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }
}
