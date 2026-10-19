package com.agenticai.core.agents.directory;

import com.agenticai.core.agents.a2a.A2aAgentCardService;
import com.agenticai.core.agents.core.Agent;
import com.agenticai.core.agents.core.AgentService;
import com.agenticai.core.json.JsonMappers;
import com.agenticai.core.sharing.PermissionLevel;
import com.agenticai.core.tenancy.ListPaging;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * The public agent directory.
 *
 * <ul>
 *   <li>{@code POST /api/agents/{id}/directory/publish|unpublish} - the owning
 *       tenant's management surface (EDITOR access on the agent, same gate as
 *       every other mutating agent endpoint).</li>
 *   <li>{@code GET /api/directory/agents[?q=]} and {@code GET
 *       /api/directory/agents/{agentId}} - the PUBLIC read surface, permitAll
 *       in {@code SecurityConfig}: discovery necessarily precedes
 *       authentication, and every field served was explicitly published by
 *       the tenant (signed snapshots, not live tenant data).</li>
 * </ul>
 */
@RestController
public class AgentDirectoryController {

    private final AgentDirectoryService directoryService;
    private final AgentService agentService;
    private final A2aAgentCardService cardService;
    private final ObjectMapper objectMapper = JsonMappers.plain();

    public AgentDirectoryController(
        AgentDirectoryService directoryService,
        AgentService agentService,
        A2aAgentCardService cardService
    ) {
        this.directoryService = directoryService;
        this.agentService = agentService;
        this.cardService = cardService;
    }

    public record PublishRequest(
        @Size(max = 200) String displayName,
        @Size(max = 4000) String description,
        @Size(max = 50) List<@Size(max = 128) String> capabilities,
        @Size(max = 1024) String homepageUrl
    ) {
    }

    @PostMapping("/api/agents/{agentId}/directory/publish")
    public Map<String, Object> publish(
        @PathVariable UUID agentId, @RequestBody(required = false) @Valid PublishRequest body,
        HttpServletRequest request
    ) {
        Agent agent = agentService.requireAccess(agentId, PermissionLevel.EDITOR);
        String base = cardService.resolvePublicBaseUrl(request);
        AgentDirectoryEntry entry = directoryService.publish(
            agent,
            body == null ? null : body.displayName(),
            body == null ? null : body.description(),
            body == null ? null : body.capabilities(),
            body == null ? null : body.homepageUrl(),
            base);
        return toResponse(entry, true);
    }

    @PostMapping("/api/agents/{agentId}/directory/unpublish")
    public Map<String, Object> unpublish(@PathVariable UUID agentId) {
        agentService.requireAccess(agentId, PermissionLevel.EDITOR);
        if (!directoryService.unpublish(agentId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "agent is not published");
        }
        return Map.of("agentId", agentId.toString(), "status", "UNPUBLISHED");
    }

    /** Tenant view: this tenant's directory entries, any status. */
    @GetMapping("/api/agents/directory/mine")
    public ResponseEntity<List<Map<String, Object>>> mine(
        @RequestParam(required = false) Integer limit,
        @RequestParam(required = false) Long offset
    ) {
        // No params = every entry, as before; limit 1..200 / offset window the updatedAt-DESC list.
        return ListPaging.window(
            directoryService.listForTenant().stream().map(e -> toResponse(e, false)).toList(),
            limit, offset, 50, 200);
    }

    // ---------------------------------------------------------- public surface

    /**
     * {@code limit} 1..100 (default 50); {@code offset} skips the newest entries. The body keeps
     * {@code entries} and {@code count} (rows in THIS page) and adds {@code total} (all matches),
     * {@code offset} and {@code hasMore}; {@code X-Total-Count} / {@code X-Has-More} mirror them.
     */
    @GetMapping("/api/directory/agents")
    public ResponseEntity<Map<String, Object>> list(
        @RequestParam(required = false) String q,
        @RequestParam(defaultValue = "50") int limit,
        @RequestParam(required = false) Long offset
    ) {
        long skip = Math.min(Math.max(offset == null ? 0L : offset, 0L), 1_000_000_000L);
        List<AgentDirectoryEntry> rows = directoryService.listPublished(q, limit, skip);
        List<Map<String, Object>> entries = rows.stream().map(e -> toResponse(e, false)).toList();
        int size = Math.max(1, Math.min(limit, AgentDirectoryService.MAX_LIST_LIMIT));
        long seen = skip + entries.size();
        long total = (skip == 0 && entries.size() < size)
            ? seen
            : Math.max(directoryService.countPublished(q), seen);
        boolean hasMore = total > seen;
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("entries", entries);
        body.put("count", entries.size());
        body.put("total", total);
        body.put("offset", skip);
        body.put("hasMore", hasMore);
        return ResponseEntity.ok()
            .header("X-Total-Count", String.valueOf(total))
            .header("X-Has-More", String.valueOf(hasMore))
            .body(body);
    }

    /**
     * One published agent's full record - including the signed card and
     * manifest snapshots. An unpublished or unknown id 404s (never reveals
     * that a listing existed once).
     */
    @GetMapping("/api/directory/agents/{agentId}")
    public Map<String, Object> get(@PathVariable UUID agentId) {
        AgentDirectoryEntry entry = directoryService.findPublished(agentId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "no such listing"));
        return toResponse(entry, true);
    }

    // ---------------------------------------------------------------- internals

    private Map<String, Object> toResponse(AgentDirectoryEntry e, boolean includeSnapshots) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("agentId", e.getAgentId().toString());
        out.put("displayName", e.getDisplayName());
        out.put("description", e.getDescription());
        out.put("capabilities", readJsonArray(e.getCapabilities()));
        out.put("homepageUrl", e.getHomepageUrl());
        out.put("status", e.getStatus().name());
        out.put("publishedAt", e.getPublishedAt());
        out.put("updatedAt", e.getUpdatedAt());
        if (includeSnapshots) {
            out.put("card", readJsonObject(e.getCardSnapshot()));
            out.put("manifest", readJsonObject(e.getManifestSnapshot()));
        }
        return out;
    }

    private Object readJsonObject(String json) {
        try {
            return json == null ? null : objectMapper.readValue(json, Map.class);
        } catch (Exception e) {
            return null;
        }
    }

    private Object readJsonArray(String json) {
        try {
            return json == null ? List.of() : objectMapper.readValue(json, List.class);
        } catch (Exception e) {
            return List.of();
        }
    }
}
