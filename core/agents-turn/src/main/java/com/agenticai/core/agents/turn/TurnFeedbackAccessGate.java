package com.agenticai.core.agents.turn;

import com.agenticai.core.agents.core.AgentService;
import com.agenticai.core.sharing.PermissionLevel;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.HandlerMapping;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Rating a turn ({@code POST /api/agents/{agentId}/turns/feedback}, {@code RlFeedbackController} in
 * core/rl) takes the same RUNNER access as chatting with the agent. core/rl cannot depend on agents
 * or sharing, so the check is an interceptor here, the same shape as {@code
 * QualityAgentAccessGate}. Whether the caller had that particular turn is checked in {@code
 * FeedbackService#record}.
 */
@Component
public class TurnFeedbackAccessGate implements HandlerInterceptor, WebMvcConfigurer {

    private final AgentService agentService;

    public TurnFeedbackAccessGate(AgentService agentService) {
        this.agentService = agentService;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(this).addPathPatterns("/api/agents/*/turns/feedback");
    }

    @Override
    public boolean preHandle(
            HttpServletRequest request, HttpServletResponse response, Object handler) {
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            return true;
        }
        UUID agentId = agentIdOf(request);
        if (agentId != null) {
            agentService.requireAccess(agentId, PermissionLevel.RUNNER);
        }
        return true;
    }

    @SuppressWarnings("unchecked")
    private static UUID agentIdOf(HttpServletRequest request) {
        Object vars = request.getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE);
        if (!(vars instanceof Map<?, ?> map)
                || ((Map<String, String>) map).get("agentId") == null) {
            return null;
        }
        try {
            return UUID.fromString(((Map<String, String>) map).get("agentId"));
        } catch (IllegalArgumentException e) {
            // Not a UUID: the controller's own path binding answers 400.
            return null;
        }
    }
}
