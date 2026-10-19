package com.agenticai.core.workflow;

import com.agenticai.core.workflow.dto.WebhookSignatureRequest;
import com.agenticai.core.workflow.dto.WebhookSignatureResponse;
import com.agenticai.core.workflow.dto.WorkflowPollTriggerRequest;
import com.agenticai.core.workflow.dto.WorkflowPollTriggerResponse;
import com.agenticai.core.workflow.dto.WorkflowTriggerChannelRequest;
import com.agenticai.core.workflow.dto.WorkflowTriggerChannelResponse;
import com.agenticai.core.workflow.dto.WorkflowWebhookResponse;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The per-node trigger sub-resources of a workflow, split out of {@link WorkflowController} with their URLs
 * unchanged: a trigger node's webhook token, its inbound channel, webhook signature verification and the HTTP
 * polling trigger.
 *
 * <p>{@code SecurityConfig} gates every write here - plus the {@code /webhook} GET, which returns the plaintext
 * trigger secret - to tenant admins. These endpoints either hand out a credential or re-point the whole tenant's
 * inbound messaging, so a per-workflow EDITOR grant is not a sufficient gate for them (an attacker is the EDITOR
 * of their own workflow by construction). See the per-method notes below and the security audit 2026-07-29
 * comments in {@code SecurityConfig}.
 */
@RestController
@RequestMapping("/api/workflows/{workflowId}/nodes/{nodeId}")
public class WorkflowNodeTriggerController {

    private final WorkflowService workflowService;

    public WorkflowNodeTriggerController(WorkflowService workflowService) {
        this.workflowService = workflowService;
    }

    // Workflow webhook trigger (#163) - "how do customers trigger it"
    // without the still-unbuilt Kafka real-channel-trigger wiring. All three
    // manage ONE trigger node's webhook (per-node workflow triggers - each
    // Trigger node in the graph mints its own independent token); the actual
    // public trigger URL they configure lives on WorkflowWebhookController at
    // /webhook/workflows/{token}.
    //
    // Security audit 2026-07-29 (F2): all three verbs, INCLUDING this GET,
    // are ROLE_tenant-admin in SecurityConfig. The GET returns the plaintext
    // trigger token - a permanent, unauthenticated, internet-reachable
    // trigger for this workflow that survives disable/re-enable - so it is
    // the same trust tier as minting it, not the "reads stay open" tier the
    // rest of this controller sits at. See WorkflowService#getWebhook.
    @GetMapping("/webhook")
    public WorkflowWebhookResponse getWebhook(
            @PathVariable UUID workflowId, @PathVariable String nodeId) {
        return workflowService.getWebhook(workflowId, nodeId);
    }

    @PostMapping("/webhook")
    public WorkflowWebhookResponse enableWebhook(
            @PathVariable UUID workflowId, @PathVariable String nodeId) {
        return workflowService.enableWebhook(workflowId, nodeId);
    }

    @DeleteMapping("/webhook")
    public WorkflowWebhookResponse disableWebhook(
            @PathVariable UUID workflowId, @PathVariable String nodeId) {
        return workflowService.disableWebhook(workflowId, nodeId);
    }

    // Per-node workflow triggers - "select the channel from where it should
    // be triggered: whatsapp, sms, call, webhook is default", now scoped to
    // ONE trigger node rather than the whole workflow (#422's original
    // one-per-workflow model). Distinct from the webhook/schedule
    // sub-resources above: this decides WHICH of webhook/whatsapp/sms/call
    // this specific trigger node owns (webhook keeps its own per-node token
    // and an added OAuth2/OIDC auth-mode option; whatsapp/sms bind inbound
    // channel messages to this node instead of a URL).
    @GetMapping("/trigger-channel")
    public WorkflowTriggerChannelResponse getTriggerChannel(
            @PathVariable UUID workflowId, @PathVariable String nodeId) {
        return workflowService.getTriggerChannel(workflowId, nodeId);
    }

    /**
     * Security audit 2026-07-29 (F1): {@code ROLE_tenant-admin} in {@code SecurityConfig},
     * alongside the webhook/schedule writes above - it was previously the only trigger write on
     * this controller that was not, and it is the most powerful of the three. Its WHATSAPP/SMS/CALL
     * branch claims the tenant's inbound channel for this one trigger node (a tenant-global
     * re-point of every inbound customer message, the same trust tier as {@code PUT
     * /api/channels/whatsapp/default-agent}), and its WEBHOOK branch writes {@code
     * webhookAuthMode}, i.e. it can downgrade OAUTH2 back to SHARED_SECRET and re-arm a token URL
     * an admin had moved to OIDC. Note the EDITOR check inside {@link
     * WorkflowService#setTriggerChannel} could never have stopped either: the attacker's own
     * workflow makes them its owner, which satisfies EDITOR by construction.
     */
    @PutMapping("/trigger-channel")
    public WorkflowTriggerChannelResponse setTriggerChannel(
            @PathVariable UUID workflowId,
            @PathVariable String nodeId,
            @Valid @RequestBody WorkflowTriggerChannelRequest request) {
        return workflowService.setTriggerChannel(
                workflowId, nodeId, request.channel(), request.webhookAuthMode());
    }

    /**
     * Webhook signature verification (V482): PUT sets the scheme + secret
     * (GITHUB/STRIPE/SLACK/HMAC_SHA256 presets), DELETE or {@code mode:"NONE"}
     * turns verification off. GET returns mode + whether a secret is stored -
     * the secret itself is write-only. Same trust tier as the webhook/token
     * endpoints above (a weaker mode re-arms unsigned deliveries), so the
     * writes are {@code PERM_MANAGE_WORKFLOWS_ADMIN} in {@code
     * ToolChannelWorkflowRules}.
     */
    @PutMapping("/webhook/signature")
    public WebhookSignatureResponse setWebhookSignature(
            @PathVariable UUID workflowId,
            @PathVariable String nodeId,
            @Valid @RequestBody WebhookSignatureRequest request) {
        return workflowService.setWebhookSignature(workflowId, nodeId, request);
    }

    @DeleteMapping("/webhook/signature")
    public WebhookSignatureResponse clearWebhookSignature(
            @PathVariable UUID workflowId, @PathVariable String nodeId) {
        return workflowService.setWebhookSignature(
                workflowId, nodeId, new WebhookSignatureRequest(WebhookSignatureMode.NONE, null));
    }

    @GetMapping("/webhook/signature")
    public WebhookSignatureResponse getWebhookSignature(
            @PathVariable UUID workflowId, @PathVariable String nodeId) {
        return workflowService.getWebhookSignature(workflowId, nodeId);
    }

    /**
     * HTTP polling trigger (V482): the trigger node polls {@code url} on an
     * interval, dedupes items by {@code idPath}, and fires a run per new
     * item. PUT (re)configures and enables; DELETE stops it (config row is
     * removed). Same unattended-execution trust tier as the schedule trigger
     * - the writes are {@code PERM_MANAGE_WORKFLOWS_ADMIN}; GET is read-open
     * (config + status, never the stored headers).
     */
    @PutMapping("/poll-trigger")
    public WorkflowPollTriggerResponse configurePollTrigger(
            @PathVariable UUID workflowId,
            @PathVariable String nodeId,
            @Valid @RequestBody WorkflowPollTriggerRequest request) {
        return workflowService.configurePollTrigger(workflowId, nodeId, request);
    }

    @DeleteMapping("/poll-trigger")
    public WorkflowPollTriggerResponse disablePollTrigger(
            @PathVariable UUID workflowId, @PathVariable String nodeId) {
        return workflowService.disablePollTrigger(workflowId, nodeId);
    }

    @GetMapping("/poll-trigger")
    public WorkflowPollTriggerResponse getPollTrigger(
            @PathVariable UUID workflowId, @PathVariable String nodeId) {
        return workflowService.getPollTrigger(workflowId, nodeId);
    }
}
