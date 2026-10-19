package com.agenticai.core.agents.branching;

import com.agenticai.core.agents.branching.ChatBranchDtos.ActivePathResponse;
import com.agenticai.core.agents.branching.ChatBranchDtos.EditRequest;
import com.agenticai.core.agents.branching.ChatBranchDtos.RegenerateRequest;
import com.agenticai.core.agents.branching.ChatBranchDtos.SiblingsResponse;
import com.agenticai.core.agents.branching.ChatBranchDtos.SwitchLeafRequest;
import com.agenticai.core.agents.dto.ChatRequest;
import com.agenticai.core.agents.turn.AgentChatStreamController;
import com.agenticai.core.security.TenantContext;
import jakarta.validation.Valid;
import java.security.Principal;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

/**
 * Chat branching routes (app.chat.branching.enabled, default off - every route here is a 404 then,
 * except the discovery call in {@link ChatBranchCapabilitiesController}).
 *
 * <p>Thin on purpose: access (RUNNER, the chat bar), the flag, validation and every status code are
 * decided in {@link ChatBranchService}, which throws only types {@code GlobalExceptionHandler}
 * already maps (IllegalArgumentException 400, ResponseStatusException 404/409). No SecurityConfig
 * entry: the paths fall through to {@code anyRequest().authenticated()}.
 *
 * <p>Edit and regenerate stream exactly like {@code POST /api/agents/{id}/chat}: after the service
 * has pointed the session at the right path they delegate to {@link AgentChatStreamController#chat}, so
 * guardrails, quotas, tools, cost accounting and the SSE contract are the ordinary chat pipeline's,
 * not a second copy of it.
 */
@RestController
@RequestMapping("/api/agents/{agentId}/sessions/{sessionId}/messages")
public class ChatBranchController {

    private final ChatBranchService service;
    private final AgentChatStreamController agentController;

    public ChatBranchController(ChatBranchService service, AgentChatStreamController agentController) {
        this.service = service;
        this.agentController = agentController;
    }

    /** The path currently shown and replayed to the model. {@code branch=active} is the only supported view. */
    @GetMapping
    public ActivePathResponse messages(
        @PathVariable UUID agentId,
        @PathVariable String sessionId,
        @RequestParam(name = "branch", defaultValue = "active") String branch
    ) {
        service.requireEnabled();
        if (!"active".equals(branch)) {
            throw new IllegalArgumentException("Only branch=active is supported.");
        }
        return service.activePath(agentId, sessionId);
    }

    /** The versions of one message, in order, with the position of this one. */
    @GetMapping("/{messageId}/siblings")
    public SiblingsResponse siblings(
        @PathVariable UUID agentId,
        @PathVariable String sessionId,
        @PathVariable UUID messageId
    ) {
        return service.siblings(agentId, sessionId, messageId);
    }

    /** Show another version: the server lands on the newest leaf beneath {@code messageId}. */
    @PutMapping("/active-leaf")
    public ActivePathResponse switchActiveLeaf(
        @PathVariable UUID agentId,
        @PathVariable String sessionId,
        @Valid @RequestBody SwitchLeafRequest request
    ) {
        return service.switchActiveLeaf(agentId, sessionId, request.messageId());
    }

    /** New text for an earlier user message; streams the new reply. The original branch is kept. */
    @PostMapping(value = "/{messageId}/edit", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public ResponseEntity<Flux<String>> edit(
        @PathVariable UUID agentId,
        @PathVariable String sessionId,
        @PathVariable UUID messageId,
        @Valid @RequestBody EditRequest request,
        Principal principal
    ) {
        ChatBranchService.PreparedTurn prepared = service.prepareEdit(agentId, sessionId, messageId, request.message());
        return runChat(agentId, prepared, request.turnRef(), request.stepEvents(), principal);
    }

    /** Another answer to the same user message; streams it. The previous answer is kept as a sibling. */
    @PostMapping(value = "/{messageId}/regenerate", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public ResponseEntity<Flux<String>> regenerate(
        @PathVariable UUID agentId,
        @PathVariable String sessionId,
        @PathVariable UUID messageId,
        @RequestBody(required = false) RegenerateRequest request,
        Principal principal
    ) {
        ChatBranchService.PreparedTurn prepared = service.prepareRegenerate(agentId, sessionId, messageId);
        return runChat(
            agentId, prepared,
            request == null ? null : request.turnRef(),
            request != null && request.stepEvents(),
            principal);
    }

    private ResponseEntity<Flux<String>> runChat(
        UUID agentId, ChatBranchService.PreparedTurn prepared, String turnRef, boolean stepEvents, Principal principal
    ) {
        ResponseEntity<Flux<String>> response;
        try {
            response = agentController.chat(
                agentId,
                new ChatRequest(prepared.sessionId(), prepared.text(), null, null, turnRef, null, null, false, stepEvents),
                principal,
                null);
        } catch (RuntimeException e) {
            // The turn was refused before it started (quota, guardrail, ...): leave the conversation as it was.
            service.restoreLeaf(agentId, prepared.sessionId(), prepared.previousLeafId());
            throw e;
        }
        Flux<String> body = response.getBody();
        if (body == null) {
            return response;
        }
        // Moderation and guardrail refusals are ordinary Fluxes that append nothing, and quota / upstream
        // failures surface only after subscription, so the catch above never sees them. Whichever way the
        // stream ends, if the live transcript is still the cut-back path the previous branch is put back.
        // doOn* rather than doFinally: it must run BEFORE the end of the stream reaches the client, whose next
        // call (the sync GET) would otherwise race it. The context is captured here because the terminal
        // signal may arrive on a reactor thread that has none.
        TenantContext captured = TenantContext.current().orElse(null);
        AtomicBoolean done = new AtomicBoolean();
        Runnable reconcile = () -> {
            if (done.compareAndSet(false, true)) {
                restoreWithContext(captured, agentId, prepared);
            }
        };
        Flux<String> guarded = body
            .doOnComplete(reconcile)
            .doOnError(e -> reconcile.run())
            .doOnCancel(reconcile);
        return new ResponseEntity<>(guarded, response.getHeaders(), response.getStatusCode());
    }

    private void restoreWithContext(TenantContext captured, UUID agentId, ChatBranchService.PreparedTurn prepared) {
        TenantContext previous = TenantContext.current().orElse(null);
        try {
            if (captured != null) {
                TenantContext.set(captured);
            }
            service.restoreIfNothingAppended(agentId, prepared.sessionId(), prepared.previousLeafId());
        } finally {
            if (previous != null) {
                TenantContext.set(previous);
            } else {
                TenantContext.clear();
            }
        }
    }
}
