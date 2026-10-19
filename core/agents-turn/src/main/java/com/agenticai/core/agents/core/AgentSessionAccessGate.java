package com.agenticai.core.agents.core;

import com.agenticai.core.agents.memory.SessionMemoryService;
import com.agenticai.core.security.TenantContext;
import com.agenticai.core.sharing.PermissionLevel;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.security.Principal;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.HandlerMapping;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Access check for one chat session's model traffic: {@code AgentSessionLlmRequestController} (the
 * final prompts, history included) and {@code AgentSessionLlmUsageController} (per-call cost). Both
 * live in {@code core/chat}, which cannot depend on agents or sharing, and checked nothing: any
 * member who knew a session id could read another user's prompts.
 *
 * <p>The caller needs VIEWER on the agent (the prompts include its configuration) and either owns
 * the session or holds EDITOR on the agent (its owner, an admin, an editor grant). Ownership is the
 * record {@link SessionMemoryService} keeps for the caller-scoped session id the chat path uses, so
 * a raw session id alone proves nothing. A denial is the usual anti-enumeration 404. Same
 * interceptor shape as {@code QualityAgentAccessGate}.
 */
@Component
public class AgentSessionAccessGate implements HandlerInterceptor, WebMvcConfigurer {

    static final String[] PATH_PATTERNS = {
        "/api/agents/*/sessions/*/llm-requests", "/api/agents/*/sessions/*/llm-requests/**",
        "/api/agents/*/sessions/*/llm-usage", "/api/agents/*/sessions/*/llm-usage/**"
    };

    private final AgentService agentService;
    private final SessionMemoryService sessionMemoryService;

    public AgentSessionAccessGate(
            AgentService agentService, SessionMemoryService sessionMemoryService) {
        this.agentService = agentService;
        this.sessionMemoryService = sessionMemoryService;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(this).addPathPatterns(PATH_PATTERNS);
    }

    @Override
    public boolean preHandle(
            HttpServletRequest request, HttpServletResponse response, Object handler) {
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            return true;
        }
        Map<String, String> vars = uriVariables(request);
        UUID agentId = parseUuid(vars.get("agentId"));
        String sessionId = vars.get("sessionId");
        if (agentId == null || !StringUtils.hasText(sessionId)) {
            // The controller's own path binding answers 400; nothing is read.
            return true;
        }
        agentService.requireAccess(agentId, PermissionLevel.VIEWER);
        if (!ownsSession(agentId, sessionId, request.getUserPrincipal())) {
            agentService.requireAccess(agentId, PermissionLevel.EDITOR);
        }
        return true;
    }

    private boolean ownsSession(UUID agentId, String sessionId, Principal principal) {
        String tenantId = TenantContext.current().map(TenantContext::getTenantId).orElse(null);
        String subject =
                principal != null && StringUtils.hasText(principal.getName())
                        ? principal.getName()
                        : TenantContext.current().map(TenantContext::getSubject).orElse(null);
        if (tenantId == null || subject == null) {
            return false;
        }
        String scoped = SessionMemoryService.scopedSessionId(tenantId, agentId, subject, sessionId);
        return sessionMemoryService.isOwnedBy(scoped, tenantId, subject);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, String> uriVariables(HttpServletRequest request) {
        Object vars = request.getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE);
        return vars instanceof Map<?, ?> map ? (Map<String, String>) map : Map.of();
    }

    private static UUID parseUuid(String raw) {
        if (raw == null) {
            return null;
        }
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
