package com.agenticai.core.agents.usermemory;

import com.agenticai.core.agents.core.AgentService;
import com.agenticai.core.agents.memory.AgentMemoryService;
import com.agenticai.core.agents.memory.MemoryCapExceededException;
import com.agenticai.core.agents.memory.TenantMemoryService;
import com.agenticai.core.agents.usermemory.UserMemoryController.FactCreateRequest;
import com.agenticai.core.agents.usermemory.UserMemoryController.FactView;
import com.agenticai.core.sharing.PermissionLevel;
import java.security.Principal;
import java.time.Instant;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;

/**
 * Adds one hand-written memory fact: the logic behind {@code POST /api/memory/facts}, shared with
 * {@link com.agenticai.core.agents.memory.importer.MemoryImportService}.
 *
 * <p>It lives in a service, not in {@link UserMemoryController}, because the jobs and worker
 * shapes scan no {@code @RestController}s ({@code JobsApplication}, {@code WorkerApplication}): an
 * importer that injected the controller made both of them fail to start. Errors are still
 * {@link ResponseStatusException}s with the statuses the endpoint has always answered, which the
 * importer maps to its skip reasons.
 */
@Service
public class UserMemoryFactService {

    static final int MAX_FACT_CHARS = 2000;

    private final ObjectProvider<AgentMemoryService> agentMemoryService;
    private final ObjectProvider<TenantMemoryService> tenantMemoryService;
    private final AgentService agentService;
    private final boolean selfServiceEnabled;
    private final int maxFactsPerAgent;
    private final int maxSharedFacts;

    public UserMemoryFactService(
        ObjectProvider<AgentMemoryService> agentMemoryService,
        ObjectProvider<TenantMemoryService> tenantMemoryService,
        AgentService agentService,
        @Value("${app.memory.self-service.enabled:true}") boolean selfServiceEnabled,
        @Value("${app.memory.self-service.max-facts-per-agent:25}") int maxFactsPerAgent,
        @Value("${app.memory.self-service.max-shared-facts:50}") int maxSharedFacts
    ) {
        this.agentMemoryService = agentMemoryService;
        this.tenantMemoryService = tenantMemoryService;
        this.agentService = agentService;
        this.selfServiceEnabled = selfServiceEnabled;
        this.maxFactsPerAgent = Math.max(0, maxFactsPerAgent);
        this.maxSharedFacts = Math.max(0, maxSharedFacts);
    }

    /** Adds one fact by hand. A private fact needs an agent the caller may run; a shared fact belongs to no agent. */
    public FactView addFact(FactCreateRequest body, Principal principal) {
        String subject = subject(principal);
        if (!selfServiceEnabled) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Memory self-service is disabled.");
        }
        String content = body == null ? null : body.content();
        if (!StringUtils.hasText(content)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Memory text cannot be empty.");
        }
        if (content.length() > MAX_FACT_CHARS) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A memory can hold at most " + MAX_FACT_CHARS + " characters.");
        }
        String text = content.strip();
        String scope = body.scope() == null ? "" : body.scope();
        Instant now = Instant.now();
        switch (scope) {
            case "agent" -> {
                UUID agentId = body.agentId();
                if (agentId == null) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Choose which agent should remember this.");
                }
                AgentMemoryService agent = agentMemoryService.getIfAvailable();
                if (agent == null) {
                    throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Memory is not available.");
                }
                // Answers 404 (AgentNotFoundException / ResourceAccessDeniedException) when the caller may not run it.
                agentService.requireAccess(agentId, PermissionLevel.RUNNER);
                try {
                    UUID id = agent.rememberOwn(agentId, subject, text, maxFactsPerAgent);
                    return new FactView(id, "agent", agentId, text, now, now, 0);
                } catch (MemoryCapExceededException e) {
                    throw new ResponseStatusException(HttpStatus.CONFLICT, e.getMessage());
                }
            }
            case "shared" -> {
                TenantMemoryService tenant = tenantMemoryService.getIfAvailable();
                if (tenant == null) {
                    throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Memory is not available.");
                }
                try {
                    UUID id = tenant.rememberOwn(subject, text, maxSharedFacts);
                    return new FactView(id, "shared", null, text, now, now, 0);
                } catch (MemoryCapExceededException e) {
                    throw new ResponseStatusException(HttpStatus.CONFLICT, e.getMessage());
                } catch (IllegalArgumentException e) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
                }
            }
            default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown memory scope.");
        }
    }

    private static String subject(Principal principal) {
        if (principal == null || !StringUtils.hasText(principal.getName())) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Sign in to manage your memory.");
        }
        return principal.getName();
    }
}
