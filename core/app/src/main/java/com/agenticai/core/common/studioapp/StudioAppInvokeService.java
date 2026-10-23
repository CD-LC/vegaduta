package com.agenticai.core.common.studioapp;

import com.agenticai.core.agents.core.Agent;
import com.agenticai.core.agents.hosted.FreeTierChatClientService;
import com.agenticai.core.agents.share.AgentShare;
import com.agenticai.core.agents.share.AgentShareService;
import com.agenticai.core.agents.turn.AgentChatService;
import com.agenticai.core.agents.turn.ChatTurnRequest;
import com.agenticai.core.agents.turn.TurnContext;
import com.agenticai.core.prompts.PromptResolver;
import com.agenticai.core.prompts.PromptSlot;
import com.agenticai.core.security.TenantContext;
import com.agenticai.core.security.TenantLimitExceededException;
import com.agenticai.core.security.TenantLimits;
import com.agenticai.core.security.TenantPlan;
import com.agenticai.core.workflow.WorkflowDefinition;
import com.agenticai.core.workflow.WorkflowService;
import com.agenticai.core.workflow.dto.WorkflowRunResponse;
import com.agenticai.core.workflow.share.WorkflowShare;
import com.agenticai.core.workflow.share.WorkflowShareController;
import com.agenticai.core.workflow.share.WorkflowShareService;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * The runtime half of published apps: what a {@code vd.call(alias)} or {@code vd.ai.chat()} turn
 * actually executes. Always called after {@link StudioAppService#resolvePublicApp} has set {@link
 * TenantContext} to the owning tenant's schema - so every dispatch runs inside the OWNER's normal
 * plan/budget/quota exactly like a share-link viewer turn (see {@code AgentShareController}'s
 * javadoc for that model).
 *
 * <p>Agent/workflow calls resolve through the connection's stored {@code share_id}: the public
 * share row is the authorization, so revoking the share on the resource's own settings page - or
 * deleting the resource - kills the app feature with the same "gone" surface, no second switch.
 */
@Service
public class StudioAppInvokeService {

    private static final Logger log = LoggerFactory.getLogger(StudioAppInvokeService.class);
    private static final Duration TURN_TIMEOUT = Duration.ofSeconds(45);
    /** Registry key of the persona of an app with no system prompt of its own ({@code prompts/en/studioapp.default_system.md}). */
    static final String DEFAULT_AI_SYSTEM_KEY = "studioapp.default_system";

    private final PromptSlot prompts = new PromptSlot();

    /** The prompt registry, so an owner can reword the default persona; absent = the shipped text. */
    @Autowired(required = false)
    void setPromptResolver(PromptResolver resolver) {
        prompts.use(resolver);
    }

    private final AgentShareService agentShareService;
    private final AgentChatService agentChatService;
    private final WorkflowShareService workflowShareService;
    private final WorkflowService workflowService;
    private final FreeTierChatClientService freeTierChatClientService;
    private final TenantLimits tenantLimits;

    public StudioAppInvokeService(
            AgentShareService agentShareService,
            AgentChatService agentChatService,
            WorkflowShareService workflowShareService,
            WorkflowService workflowService,
            FreeTierChatClientService freeTierChatClientService,
            TenantLimits tenantLimits) {
        this.agentShareService = agentShareService;
        this.agentChatService = agentChatService;
        this.workflowShareService = workflowShareService;
        this.workflowService = workflowService;
        this.freeTierChatClientService = freeTierChatClientService;
        this.tenantLimits = tenantLimits;
    }

    public sealed interface InvokeResult permits InvokeResult.Ok, InvokeResult.Failed {
        record Ok(String text) implements InvokeResult {}

        record Failed(String code, String message) implements InvokeResult {}
    }

    /**
     * One {@code vd.call(alias, input)} turn: render the builder's request template, dispatch to
     * the connection's agent/workflow, then pick the configured part of the response back out.
     */
    public InvokeResult call(
            StudioApp app,
            StudioAppConnection connection,
            String input,
            Map<String, Object> data,
            StudioAppUser user,
            String clientIp) {
        String rendered =
                StudioAppPayload.renderRequest(
                        connection.getRequestTemplate(),
                        input,
                        data,
                        user == null
                                ? null
                                : new StudioAppPayload.AppUserView(
                                        user.getId().toString(),
                                        user.getEmail(),
                                        user.getDisplayName()));
        if (rendered.isBlank() || rendered.length() > 2000) {
            return new InvokeResult.Failed("invalid", "empty_or_too_long");
        }
        // Conversation memory stays per-END-USER when signed in, per-IP
        // otherwise - one visitor can't bleed context into another's turns.
        String sessionKey =
                user != null
                        ? "app:" + app.getSlug() + ":u:" + user.getId()
                        : "app:" + app.getSlug() + ":ip:" + WorkflowShareService.ipHash(clientIp);
        String subjectKey = "studio-app:" + app.getId();
        try {
            return switch (connection.getKind()) {
                case AGENT -> callAgent(connection, sessionKey, subjectKey, rendered);
                case WORKFLOW -> callWorkflow(connection, sessionKey, rendered);
            };
        } catch (TenantLimitExceededException e) {
            return new InvokeResult.Failed(
                    "quota_exhausted", "The app owner's usage quota is exhausted for now.");
        } catch (RuntimeException e) {
            log.warn(
                    "Studio app call failed (app {}, alias {})",
                    app.getSlug(),
                    connection.getAlias(),
                    e);
            return new InvokeResult.Failed(
                    "upstream_failed", "The service behind this app failed to answer.");
        }
    }

    private InvokeResult callAgent(
            StudioAppConnection connection, String sessionKey, String subjectKey, String message) {
        Optional<AgentShare> share = agentShareService.resolveByShareId(connection.getShareId());
        if (share.isEmpty()
                || share.get().isRevoked()
                || !share.get().getAgentId().equals(connection.getTargetId())) {
            return new InvokeResult.Failed(
                    "expired", "This connection was switched off by the app owner.");
        }
        Optional<Agent> agent = agentShareService.loadSharedAgent(connection.getTargetId());
        if (agent.isEmpty()) {
            return new InvokeResult.Failed(
                    "expired", "This connection was switched off by the app owner.");
        }
        agentShareService.recordViewer(share.get());
        ChatTurnRequest chatTurn =
                ChatTurnRequest.builder(connection.getTargetId(), sessionKey, subjectKey, message)
                        .build();
        TurnContext turnContext =
                TurnContext.forEntry(TenantContext.current().orElse(null), chatTurn);
        String reply =
                agentChatService
                        .chat(turnContext, chatTurn)
                        .collect(Collectors.joining())
                        .block(TURN_TIMEOUT);
        if (reply == null || reply.isBlank()) {
            return new InvokeResult.Failed("no_reply", "The service returned nothing.");
        }
        return new InvokeResult.Ok(
                StudioAppPayload.extractResponse(connection.getResponsePath(), "reply", reply));
    }

    private InvokeResult callWorkflow(
            StudioAppConnection connection, String sessionKey, String input) {
        Optional<WorkflowShare> share =
                workflowShareService.resolveByShareId(connection.getShareId());
        if (share.isEmpty()
                || share.get().isRevoked()
                || !share.get().getWorkflowId().equals(connection.getTargetId())) {
            return new InvokeResult.Failed(
                    "expired", "This connection was switched off by the app owner.");
        }
        Optional<WorkflowDefinition> workflow =
                workflowShareService.loadSharedWorkflow(connection.getTargetId());
        if (workflow.isEmpty()) {
            return new InvokeResult.Failed(
                    "expired", "This connection was switched off by the app owner.");
        }
        workflowShareService.recordViewer(share.get());
        WorkflowRunResponse response =
                workflowService.run(
                        connection.getTargetId(),
                        null,
                        input,
                        "studio-app-share:" + connection.getShareId(),
                        null,
                        List.of());
        String text = WorkflowShareController.deriveResultText(response);
        return new InvokeResult.Ok(
                StudioAppPayload.extractResponse(connection.getResponsePath(), "resultText", text));
    }

    /**
     * One {@code vd.ai.chat()} turn through the platform-hosted model pool - "VegaDūta-hosted
     * models inside the app". Rides {@link FreeTierChatClientService}'s existing hosted chain
     * (plan-aware, quota-metered, provider-failover built in); the builder's system prompt shapes
     * the persona, the end user's text is the user turn.
     */
    public InvokeResult aiChat(StudioApp app, String prompt, StudioAppUser user) {
        if (prompt == null || prompt.isBlank() || prompt.length() > 4000) {
            return new InvokeResult.Failed("invalid", "empty_or_too_long");
        }
        String tenantId = TenantContext.current().map(TenantContext::getTenantId).orElse(null);
        if (tenantId == null) {
            return new InvokeResult.Failed("upstream_failed", "No tenant context.");
        }
        try {
            TenantPlan plan = tenantLimits.plan(tenantId);
            String system =
                    app.getAiSystemPrompt() == null || app.getAiSystemPrompt().isBlank()
                            ? prompts.text(DEFAULT_AI_SYSTEM_KEY)
                            : app.getAiSystemPrompt();
            List<ChatClient> chain =
                    freeTierChatClientService.buildClientChainForFreeTenant(
                            tenantId, plan, app.getAiMaxTokens(), 0.7, 1.0);
            for (ChatClient client : chain) {
                try {
                    String content = client.prompt().system(system).user(prompt).call().content();
                    if (content != null && !content.isBlank()) {
                        return new InvokeResult.Ok(content.trim());
                    }
                } catch (RuntimeException e) {
                    log.warn("Hosted-pool link failed for app {} - trying next", app.getSlug(), e);
                }
            }
            return new InvokeResult.Failed("no_reply", "The AI returned nothing.");
        } catch (TenantLimitExceededException e) {
            return new InvokeResult.Failed(
                    "quota_exhausted", "The app owner's AI quota is exhausted for now.");
        } catch (RuntimeException e) {
            log.warn("Studio app AI turn failed for app {}", app.getSlug(), e);
            return new InvokeResult.Failed("upstream_failed", "The AI service failed to answer.");
        }
    }
}
