package com.agenticai.core.agents.turn;

import com.agenticai.core.agents.core.AgentController;
import com.agenticai.core.agents.core.AgentService;
import com.agenticai.core.agents.dto.ChatRequest;
import com.agenticai.core.agents.resumable.ResumableChatStreamService;
import com.agenticai.core.sharing.PermissionLevel;
import jakarta.validation.Valid;
import java.security.Principal;
import java.time.Duration;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

/**
 * The authenticated chat SSE endpoint, {@code POST /api/agents/{agentId}/chat}. It used to be a
 * method of {@link AgentController}; ADR-0023 moved it next to the turn path ({@code
 * core/agents-turn}) so the kernel controller no longer depends on {@link AgentChatService}. The
 * path, the request body, the headers ({@link AgentController#SESSION_ID_HEADER}, {@link
 * ResumableChatStreamService#OPT_IN_HEADER}) and the stream format ({@link
 * AgentController#STREAM_ERROR_MARKER}) are unchanged, and the OpenAPI generator publishes this
 * class as {@code AgentController} ({@code OpenApiControllerAliases}), so the operation keeps its
 * {@code AgentController_chat} id and SDK method. The class-level mapping is the full path, so no
 * other controller shares this base path.
 */
@RestController
@RequestMapping("/api/agents/{agentId}/chat")
public class AgentChatStreamController {

    private final AgentService agentService;

    /**
     * Builds the chat SSE response. Carries the liveness-beat cadence for step-event turns - see
     * {@code AgentStepEventEmitter#heartbeat} and {@code app.chat.sse.step-heartbeat-interval} in
     * application.yml, bound here so the operator override lives next to {@code
     * buffered-keepalive-interval}, the sibling timeout it complements.
     */
    private final AgentChatStreamResponder chatStream;

    /**
     * Resumable chat streams (app.chat.resumable-streams.*, default off). Setter-injected and
     * nullable; null or flag-off = the unchanged stream path.
     */
    private ResumableChatStreamService resumableChatStreams;

    public AgentChatStreamController(
            AgentService agentService,
            AgentChatService agentChatService,
            @Value("${app.chat.sse.step-heartbeat-interval:15s}") Duration stepHeartbeatInterval) {
        this.agentService = agentService;
        this.chatStream = new AgentChatStreamResponder(agentChatService, stepHeartbeatInterval);
    }

    @Autowired(required = false)
    public void setResumableChatStreams(ResumableChatStreamService resumableChatStreams) {
        this.resumableChatStreams = resumableChatStreams;
    }

    @PostMapping(produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public ResponseEntity<Flux<String>> chat(
            @PathVariable UUID agentId,
            @Valid @RequestBody ChatRequest request,
            Principal principal,
            @RequestHeader(name = ResumableChatStreamService.OPT_IN_HEADER, required = false)
                    String resumableOptIn) {
        // Cross-user resource sharing retrofit (Round 2) - RUNNER check at THIS
        // controller entry point deliberately, not inside AgentChatService#chat
        // itself: that method is ALSO the universal chat entry point for every
        // omnichannel webhook (WhatsApp/Telegram/Discord/Slack/etc.), which
        // authenticate via a synthetic per-channel TenantContext subject (e.g.
        // "channel-requested-by:<phoneNumberId>", see
        // MetaWhatsAppWebhookController), never a real grantee/owner subject -
        // gating requireAccess inside AgentChatService#chat would 404 every
        // single inbound customer message to a newly-created private agent
        // connected to a channel. This endpoint, by contrast, is only ever
        // reached by a real authenticated tenant member (web console / direct
        // API call, real Keycloak subject in TenantContext via
        // TenantContextFilter), so it's the correct - and only - place to
        // enforce "can this human actually chat with this agent".
        agentService.requireAccess(agentId, PermissionLevel.RUNNER);
        return chatStream.respond(agentId, request, principal, resumableOptIn, resumableChatStreams);
    }
}
