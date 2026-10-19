package com.agenticai.core.agents.crew;

import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Marks agents as callable specialists (crew handoff):
 *
 * <ul>
 *   <li>GET /api/agents/{agentId}/specialties - the agent's roles (VIEWER)</li>
 *   <li>PUT /api/agents/{agentId}/specialties - replace the set (EDITOR + PERM_MANAGE_AGENTS)</li>
 *   <li>GET /api/agents/specialties/tags - distinct role tags in the tenant</li>
 * </ul>
 *
 * Authorization lives in {@link AgentSpecialtyService}; the PUT additionally needs
 * PERM_MANAGE_AGENTS in SecurityConfig (same posture as the agent schedule / triggers, since a
 * specialist becomes callable by other agents).
 */
@RestController
@RequestMapping("/api/agents")
public class AgentSpecialtyController {

    public record SpecialtyDto(String capabilityTag, String description) {}

    public record SpecialtiesRequest(List<SpecialtyDto> specialties) {}

    public record SpecialtiesResponse(UUID agentId, List<SpecialtyDto> specialties) {}

    private final AgentSpecialtyService service;

    public AgentSpecialtyController(AgentSpecialtyService service) {
        this.service = service;
    }

    @GetMapping("/{agentId}/specialties")
    public SpecialtiesResponse get(@PathVariable UUID agentId) {
        return toResponse(agentId, service.list(agentId));
    }

    @PutMapping("/{agentId}/specialties")
    public SpecialtiesResponse put(@PathVariable UUID agentId, @RequestBody SpecialtiesRequest request) {
        List<AgentSpecialtyService.Specialty> requested = request == null || request.specialties() == null
            ? null
            : request.specialties().stream()
                .map(s -> s == null ? null : new AgentSpecialtyService.Specialty(s.capabilityTag(), s.description()))
                .toList();
        return toResponse(agentId, service.replaceForAgent(agentId, requested));
    }

    @GetMapping("/specialties/tags")
    public List<String> tags() {
        return service.listTenantTags();
    }

    private static SpecialtiesResponse toResponse(UUID agentId, List<AgentSpecialization> rows) {
        return new SpecialtiesResponse(agentId,
            rows.stream().map(r -> new SpecialtyDto(r.getCapabilityTag(), r.getDescription())).toList());
    }
}
