package com.agenticai.core.quality.access;

import com.agenticai.core.sharing.PermissionLevel;
import com.agenticai.core.sharing.ResourceAccessDeniedException;
import com.agenticai.core.sharing.ResourceAccessService;
import com.agenticai.core.sharing.ResourceType;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.HandlerMapping;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Per-agent access check for the WHOLE {@code /api/agents/{agentId}/quality/**}
 * surface (datasets, runs, trajectory eval, gate, reliability, credibility,
 * simulations, scores) - bug B7.
 *
 * <p>These controllers only applied the plan gate, so a tenant member with no
 * access to an agent (the agent itself, its hooks and versions all 404 for
 * them) could still list that agent's datasets, read its trajectories, create
 * and DELETE datasets and queue spend-bearing runs. This interceptor applies
 * the same check the hooks / policies / MCP-exposure controllers use -
 * {@code ResourceAccessService#requireAccess(AGENT, ...)} - before any
 * controller method runs: {@code VIEWER} for GET/HEAD/OPTIONS, {@code EDITOR}
 * for every other method (create, update, delete, queue/cancel a run).
 *
 * <p>It is an interceptor over the path pattern (not per-controller calls)
 * deliberately: every present and future controller under the prefix is
 * covered without each one having to remember, and the controllers'
 * constructors stay untouched. A denied caller gets the same anti-enumeration
 * 404 ({@link ResourceAccessDeniedException}) as the sibling controllers, and
 * so does an agent id that does not exist in the caller's tenant schema.
 */
@Component
public class QualityAgentAccessGate implements HandlerInterceptor, WebMvcConfigurer {

    /** Every controller in this module that takes an agent id maps under this prefix. */
    public static final String[] PATH_PATTERNS = {
        "/api/agents/*/quality", "/api/agents/*/quality/**"
    };

    private final ResourceAccessService accessService;
    private final AgentOwnerLookup ownerLookup;

    public QualityAgentAccessGate(ResourceAccessService accessService, AgentOwnerLookup ownerLookup) {
        this.accessService = accessService;
        this.ownerLookup = ownerLookup;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(this).addPathPatterns(PATH_PATTERNS);
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        // CORS preflight carries no credentials and must reach the CORS processor untouched.
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            return true;
        }
        UUID agentId = agentIdOf(request);
        if (agentId == null) {
            // Not a UUID: the controller's own path-variable binding answers 400 and nothing is read.
            return true;
        }
        Optional<AgentOwnerLookup.AgentOwner> owner = ownerLookup.find(agentId);
        if (owner.isEmpty()) {
            throw new ResourceAccessDeniedException();
        }
        accessService.requireAccess(ResourceType.AGENT, agentId, owner.get().createdBy(), requiredLevel(request));
        return true;
    }

    static PermissionLevel requiredLevel(HttpServletRequest request) {
        String method = request.getMethod();
        boolean read = "GET".equalsIgnoreCase(method) || "HEAD".equalsIgnoreCase(method);
        return read ? PermissionLevel.VIEWER : PermissionLevel.EDITOR;
    }

    @SuppressWarnings("unchecked")
    private static UUID agentIdOf(HttpServletRequest request) {
        Object vars = request.getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE);
        if (!(vars instanceof Map<?, ?> map)) {
            return null;
        }
        Object raw = ((Map<String, String>) map).get("agentId");
        if (raw == null) {
            return null;
        }
        try {
            return UUID.fromString(raw.toString());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
