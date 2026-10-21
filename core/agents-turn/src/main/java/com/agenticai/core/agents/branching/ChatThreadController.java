package com.agenticai.core.agents.branching;

import com.agenticai.core.agents.branching.ChatBranchDtos.ThreadsResponse;
import java.time.Instant;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The caller's chat sessions (app.chat.branching.enabled, default off - both routes are a 404 then, see
 * {@link ChatBranchFlagGate}; the console treats that as "keep the local list only").
 *
 * <p>Thin like {@link ChatBranchController}: the flag, the owner check and every status code are decided in
 * {@link ChatThreadService}, which throws only types {@code GlobalExceptionHandler} already maps
 * (IllegalArgumentException 400, ResponseStatusException 404). Security: {@code TenantFeatureRules} leaves
 * the delete on {@code authenticated()} because the service only ever touches the caller's own session.
 */
@RestController
@RequestMapping("/api/chat/threads")
public class ChatThreadController {

    private final ChatThreadService service;

    public ChatThreadController(ChatThreadService service) {
        this.service = service;
    }

    /**
     * The caller's sessions, newest activity first. {@code limit} 1..200 (default 50); {@code before} is the
     * previous page's {@code nextBefore} (an ISO-8601 instant on {@code updatedAt}).
     */
    @GetMapping
    public ThreadsResponse list(
        @RequestParam(name = "limit", required = false) Integer limit,
        @RequestParam(name = "before", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant before
    ) {
        return service.list(limit, before);
    }

    /** Removes one of the caller's sessions with every message of its tree; 404 for any other session. */
    @DeleteMapping("/{agentId}/{sessionId}")
    public ResponseEntity<Void> delete(@PathVariable UUID agentId, @PathVariable String sessionId) {
        service.delete(agentId, sessionId);
        return ResponseEntity.noContent().build();
    }
}
