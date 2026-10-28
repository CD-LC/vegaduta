package com.agenticai.core.agents.turn;

import com.agenticai.core.agents.core.AgentService;
import com.agenticai.core.agents.memory.SessionTaintStore;
import com.agenticai.core.sharing.PermissionLevel;
import java.security.Principal;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Inspect and explicitly clear a chat session's conversation-level taint marker (see {@link
 * SessionTaintStore}).
 *
 * <p>The marker is keyed by the SEC-004 scoped session id, derived from the caller, so the user who
 * owns the conversation reaches only their own (RUNNER on the agent, the same bar as speaking in
 * the session). A tenant admin may act for another user of THEIR tenant by passing that user's
 * subject as {@code subject}; anyone else passing it is refused. Clearing is audited and only
 * records a human decision that the conversation is trusted again - it does not clean the history.
 */
@RestController
@RequestMapping("/api/agents/{agentId}/chat/sessions/{sessionId}/taint")
public class SessionTaintController {

    private final AgentService agentService;
    private final ObjectProvider<SessionTaintStore> store;

    public SessionTaintController(AgentService agentService, ObjectProvider<SessionTaintStore> store) {
        this.agentService = agentService;
        this.store = store;
    }

    @GetMapping
    public ResponseEntity<Map<String, Object>> status(
            @PathVariable UUID agentId,
            @PathVariable String sessionId,
            @RequestParam(required = false) String subject,
            Principal principal,
            Authentication authentication) {
        agentService.requireAccess(agentId, PermissionLevel.RUNNER);
        SessionTaintStore taintStore = store.getIfAvailable();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("sessionId", sessionId);
        body.put("persistenceEnabled", taintStore != null && taintStore.enabled());
        if (taintStore == null) {
            body.put("tainted", false);
            return ResponseEntity.ok(body);
        }
        String scoped = scoped(agentId, sessionId, subject, principal, authentication);
        taintStore
                .load(scoped)
                .ifPresentOrElse(
                        m -> {
                            body.put("tainted", true);
                            body.put("sources", m.sources());
                            body.put("firstTaintedAt", m.firstTaintedAt().toString());
                            body.put("lastTaintedAt", m.lastTaintedAt().toString());
                        },
                        () -> body.put("tainted", false));
        return ResponseEntity.ok(body);
    }

    @DeleteMapping
    public ResponseEntity<Map<String, Object>> clear(
            @PathVariable UUID agentId,
            @PathVariable String sessionId,
            @RequestParam(required = false) String subject,
            Principal principal,
            Authentication authentication) {
        agentService.requireAccess(agentId, PermissionLevel.RUNNER);
        SessionTaintStore taintStore = store.getIfAvailable();
        if (taintStore == null) {
            return ResponseEntity.ok(Map.of("sessionId", sessionId, "cleared", false));
        }
        String scoped = scoped(agentId, sessionId, subject, principal, authentication);
        String actor = principal != null ? principal.getName() : "unknown";
        boolean removed =
                taintStore.clear(
                        scoped,
                        actor,
                        agentId + "/" + sessionId + (subject != null ? " (for " + subject + ")" : ""));
        return ResponseEntity.ok(Map.of("sessionId", sessionId, "cleared", removed));
    }

    private static String scoped(
            UUID agentId,
            String sessionId,
            String subject,
            Principal principal,
            Authentication authentication) {
        Principal effective = principal;
        if (subject != null && !subject.isBlank()) {
            if (!isTenantAdmin(authentication)) {
                throw new org.springframework.security.access.AccessDeniedException(
                        "only a tenant admin may act for another user");
            }
            effective = () -> subject;
        }
        return AgentChatSessionController.scopedMemorySessionId(agentId, sessionId, effective);
    }

    private static boolean isTenantAdmin(Authentication authentication) {
        if (authentication == null || authentication.getAuthorities() == null) {
            return false;
        }
        for (GrantedAuthority a : authentication.getAuthorities()) {
            String name = a.getAuthority();
            if ("ROLE_tenant-admin".equals(name) || "ROLE_platform-admin".equals(name)) {
                return true;
            }
        }
        return false;
    }
}
