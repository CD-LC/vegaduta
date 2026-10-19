package com.agenticai.core.agents.usermemory;

import com.agenticai.core.agents.core.AgentService;
import com.agenticai.core.agents.memory.AgentMemoryEntry;
import com.agenticai.core.agents.memory.AgentMemoryService;
import com.agenticai.core.agents.memory.MemoryBlocks;
import com.agenticai.core.agents.memory.TenantMemoryEntry;
import com.agenticai.core.agents.memory.TenantMemoryService;
import com.agenticai.core.graphmemory.GraphMemoryService;
import com.agenticai.core.security.TenantContext;
import com.agenticai.core.sharing.PermissionLevel;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.Principal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * The signed-in user's own view of what the platform remembers about them (gap-analysis item 11):
 * editable always-in-context blocks (persona, user_profile, project_notes, per-project notes) plus the
 * individual facts agents saved via {@code remember_fact}, which can be edited or deleted.
 *
 * <p>Everything is scoped to the caller's own {@code subjectKey} - the Keycloak principal name, the same
 * key chat uses - so there is no way to address another user's rows. Needs the "knowledge" profile
 * (the memory services); without it {@code available} is false and the lists are empty. Facts written
 * by agents are also mirrored into graph memory; editing or deleting here changes the vector row only,
 * unless {@code app.graph-memory.user-forget-sync.enabled} (default off) is on, in which case the graph
 * edges the old text supported are invalidated too, best effort (see {@code GraphMemoryService#forgetFactText}).
 * An edit does not write graph triples for the NEW text; the graph picks the fact up again only when an
 * agent next saves it.
 *
 * <p>Self-service additions ({@code app.memory.self-service.enabled}, default on): add a fact by hand
 * ({@code POST /facts}), forget everything ({@code DELETE /api/memory?confirm=true}) and download a
 * copy ({@code GET /export}). With the flag off those three answer 404 and nothing else changes.
 * A hand-added fact never evicts another user's memory: each caller may keep at most
 * {@code app.memory.self-service.max-facts-per-agent} (25) private facts per agent and
 * {@code app.memory.self-service.max-shared-facts} (50) shared facts, answered with 409 beyond that.
 */
@RestController
@RequestMapping("/api/memory")
public class UserMemoryController {

    private static final Logger log = LoggerFactory.getLogger(UserMemoryController.class);
    private static final int MAX_FACT_CHARS = UserMemoryFactService.MAX_FACT_CHARS;
    static final String EXPORT_FILENAME = "memory-export.json";

    private final ObjectProvider<AgentMemoryService> agentMemoryService;
    private final ObjectProvider<TenantMemoryService> tenantMemoryService;
    private final ObjectProvider<GraphMemoryService> graphMemoryService;
    private final AgentService agentService;
    private final UserMemoryFactService facts;
    private final boolean selfServiceEnabled;
    private final boolean graphForgetSyncEnabled;

    public UserMemoryController(
        ObjectProvider<AgentMemoryService> agentMemoryService,
        ObjectProvider<TenantMemoryService> tenantMemoryService,
        ObjectProvider<GraphMemoryService> graphMemoryService,
        AgentService agentService,
        UserMemoryFactService facts,
        @Value("${app.memory.self-service.enabled:true}") boolean selfServiceEnabled,
        @Value("${app.graph-memory.user-forget-sync.enabled:false}") boolean graphForgetSyncEnabled
    ) {
        this.agentMemoryService = agentMemoryService;
        this.tenantMemoryService = tenantMemoryService;
        this.graphMemoryService = graphMemoryService;
        this.agentService = agentService;
        this.facts = facts;
        this.selfServiceEnabled = selfServiceEnabled;
        this.graphForgetSyncEnabled = graphForgetSyncEnabled;
    }

    public record BlockView(String label, String content, Instant updatedAt) {
    }

    /** {@code scope} is "agent" (private to {@code agentId}) or "shared" (tenant-wide). */
    public record FactView(UUID id, String scope, UUID agentId, String content, Instant createdAt, Instant lastUsedAt, int useCount) {
    }

    public record Limits(int maxCharsPerBlock, int maxProjectBlocks, int maxFactChars) {
    }

    /**
     * {@code selfServiceEnabled}: the add-fact / forget-all / export endpoints are live (otherwise they 404).
     * {@code activeProjectEnabled}: the reserved {@code active_project} block can be written (otherwise 400).
     */
    public record MemoryOverview(
        boolean available, boolean blocksEnabled, Limits limits, List<BlockView> blocks, List<FactView> facts,
        boolean selfServiceEnabled, boolean activeProjectEnabled
    ) {
    }

    public record ContentRequest(String content) {
    }

    /** {@code scope} "agent" needs {@code agentId} (an agent the caller may run); "shared" ignores it. */
    public record FactCreateRequest(String content, String scope, UUID agentId) {
    }

    /**
     * Rows removed by forget-all, per store. Each store is erased independently, so one failing does not
     * stop the others: {@code agentErased} / {@code sharedErased} / {@code graphErased} are false only
     * when that store is present and its erase failed (its count is then 0). A client must check all
     * three before telling the user everything is gone.
     */
    public record ForgetAllResult(
        int agentFacts, int sharedAndBlocks, int graphEntities, boolean graphErased, boolean agentErased, boolean sharedErased
    ) {
    }

    public record MemoryExport(Instant exportedAt, List<BlockView> blocks, List<FactView> facts) {
    }

    @GetMapping
    public MemoryOverview overview(Principal principal) {
        String subject = subject(principal);
        TenantMemoryService tenant = tenantMemoryService.getIfAvailable();
        AgentMemoryService agent = agentMemoryService.getIfAvailable();
        if (tenant == null && agent == null) {
            return new MemoryOverview(
                false, false, new Limits(0, 0, MAX_FACT_CHARS), List.of(), List.of(), selfServiceEnabled, false);
        }
        List<BlockView> blocks = new ArrayList<>();
        if (tenant != null && tenant.blocksEnabled()) {
            tenant.listBlocks(subject).forEach(b -> blocks.add(new BlockView(b.label(), b.content(), b.updatedAt())));
        }
        List<FactView> facts = new ArrayList<>();
        if (agent != null) {
            for (AgentMemoryEntry m : agent.listAllForSubject(subject)) {
                facts.add(new FactView(m.id(), "agent", m.agentId(), m.content(), m.createdAt(), m.lastUsedAt(), m.useCount()));
            }
        }
        if (tenant != null) {
            for (TenantMemoryEntry m : tenant.listFactsForSubject(subject)) {
                facts.add(new FactView(m.id(), "shared", m.createdByAgentId(), m.content(), m.createdAt(), m.lastUsedAt(), m.useCount()));
            }
        }
        facts.sort((a, b) -> b.createdAt().compareTo(a.createdAt()));
        int maxChars = tenant == null ? 0 : tenant.maxCharsPerBlock();
        int maxProjects = tenant == null ? 0 : tenant.maxProjectBlocks();
        return new MemoryOverview(
            true, tenant != null && tenant.blocksEnabled(), new Limits(maxChars, maxProjects, MAX_FACT_CHARS), blocks, facts,
            selfServiceEnabled, tenant != null && tenant.blocksEnabled() && tenant.activeProjectEnabled());
    }

    /** Adds one fact by hand; the logic is in {@link UserMemoryFactService} (shared with the memory importer). */
    @PostMapping("/facts")
    public ResponseEntity<FactView> addFact(@RequestBody FactCreateRequest body, Principal principal) {
        return ResponseEntity.status(HttpStatus.CREATED).body(facts.addFact(body, principal));
    }

    /**
     * Forgets everything about the caller: every agent's private facts, the shared facts, the editable
     * blocks and (when graph memory is on) the graph entities. Needs {@code confirm=true} so a stray
     * DELETE on the collection cannot wipe it.
     */
    @DeleteMapping
    public ForgetAllResult forgetAll(
        @RequestParam(name = "confirm", required = false, defaultValue = "false") boolean confirm, Principal principal
    ) {
        String subject = subject(principal);
        requireSelfService();
        if (!confirm) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Pass confirm=true to erase all of your memory.");
        }
        AgentMemoryService agent = agentMemoryService.getIfAvailable();
        TenantMemoryService tenant = tenantMemoryService.getIfAvailable();
        // Each store on its own: a failure in one must not leave the caller with a bare 500 and no idea what is gone.
        int agentFacts = 0;
        boolean agentErased = true;
        if (agent != null) {
            try {
                agentFacts = agent.forgetAllForSubject(subject);
            } catch (Exception e) {
                agentErased = false;
                log.warn("Self-service forget-all: agent memory erase failed", e);
            }
        }
        int sharedAndBlocks = 0;
        boolean sharedErased = true;
        if (tenant != null) {
            try {
                sharedAndBlocks = tenant.forgetAllForSubject(subject);
            } catch (Exception e) {
                sharedErased = false;
                log.warn("Self-service forget-all: shared memory erase failed", e);
            }
        }
        int graphEntities = 0;
        boolean graphErased = true;
        GraphMemoryService graph = graphMemoryService.getIfAvailable();
        if (graph != null) {
            try {
                graphEntities = graph.eraseSubject(subject);
            } catch (Exception e) {
                // eraseSubject does not fail open. Report it instead of failing the whole request.
                graphErased = false;
                log.warn("Self-service forget-all: graph erase failed", e);
            }
        }
        // The durable trace of a full self-erasure: which tenant, who (hashed, never the raw key) and what went.
        log.info("Self-service forget-all: tenant={} subject#={} agentFacts={} (ok={}) sharedAndBlocks={} (ok={}) "
                + "graphEntities={} (ok={})",
            TenantContext.current().map(TenantContext::getTenantId).orElse("unknown"), subjectFingerprint(subject),
            agentFacts, agentErased, sharedAndBlocks, sharedErased, graphEntities, graphErased);
        return new ForgetAllResult(agentFacts, sharedAndBlocks, graphEntities, graphErased, agentErased, sharedErased);
    }

    /** First 12 hex chars of SHA-256(subject): enough to correlate log lines, not to recover the identity. */
    static String subjectFingerprint(String subject) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(subject.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (int i = 0; i < 6; i++) {
                hex.append(String.format("%02x", digest[i]));
            }
            return hex.toString();
        } catch (Exception e) {
            return "unknown";
        }
    }

    /** Everything {@link #overview} lists, as a JSON download. */
    @GetMapping("/export")
    public ResponseEntity<MemoryExport> export(Principal principal) {
        subject(principal);
        requireSelfService();
        MemoryOverview o = overview(principal);
        return ResponseEntity.ok()
            .contentType(MediaType.APPLICATION_JSON)
            .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + EXPORT_FILENAME + "\"")
            .body(new MemoryExport(Instant.now(), o.blocks(), o.facts()));
    }

    @PutMapping("/blocks/{label}")
    public BlockView putBlock(@PathVariable String label, @RequestBody ContentRequest body, Principal principal) {
        String subject = subject(principal);
        TenantMemoryService tenant = blocksService();
        String normalized = MemoryBlocks.normalizeLabel(label);
        if (normalized == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown memory block label.");
        }
        try {
            tenant.putBlock(subject, normalized, body == null ? null : body.content());
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
        return tenant.listBlocks(subject).stream()
            .filter(b -> b.label().equals(normalized))
            .findFirst()
            .map(b -> new BlockView(b.label(), b.content(), b.updatedAt()))
            .orElse(new BlockView(normalized, "", null));
    }

    @DeleteMapping("/blocks/{label}")
    public ResponseEntity<Void> deleteBlock(@PathVariable String label, Principal principal) {
        String subject = subject(principal);
        blocksService().deleteBlock(subject, label);
        return ResponseEntity.noContent().build();
    }

    @PatchMapping("/facts/{scope}/{id}")
    public ResponseEntity<Void> editFact(
        @PathVariable String scope, @PathVariable UUID id, @RequestBody ContentRequest body, Principal principal
    ) {
        String subject = subject(principal);
        String content = body == null ? null : body.content();
        if (!StringUtils.hasText(content)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Memory text cannot be empty. Delete it instead.");
        }
        if (content.length() > MAX_FACT_CHARS) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A memory can hold at most " + MAX_FACT_CHARS + " characters.");
        }
        GraphForget graphForget = planGraphForget(scope, id, subject);
        boolean changed = switch (scope) {
            case "agent" -> requireAgent().updateForSubject(id, subject, content.strip());
            case "shared" -> requireTenant().updateFactForSubject(id, subject, content.strip());
            default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown memory scope.");
        };
        if (changed) {
            syncGraphForget(graphForget, subject, content.strip());
        }
        return changed ? ResponseEntity.noContent().build() : ResponseEntity.notFound().build();
    }

    @DeleteMapping("/facts/{scope}/{id}")
    public ResponseEntity<Void> deleteFact(@PathVariable String scope, @PathVariable UUID id, Principal principal) {
        String subject = subject(principal);
        GraphForget graphForget = planGraphForget(scope, id, subject);
        boolean changed = switch (scope) {
            case "agent" -> requireAgent().forgetForSubject(id, subject);
            case "shared" -> requireTenant().forgetFactForSubject(id, subject);
            default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown memory scope.");
        };
        if (changed) {
            syncGraphForget(graphForget, subject, null);
        }
        return changed ? ResponseEntity.noContent().build() : ResponseEntity.notFound().build();
    }

    /**
     * What the graph sync needs, captured BEFORE the fact is changed: the old text, its tier
     * ({@code tierAgentId} null = tenant-shared) and the caller's other facts in that tier.
     */
    private record GraphForget(GraphMemoryService graph, UUID tierAgentId, String oldContent, List<String> otherTexts) {
    }

    /**
     * Null when the sync is off ({@code app.graph-memory.user-forget-sync.enabled}), graph memory is not
     * running, the fact is not the caller's, or the lookup fails - in every such case the edit/delete
     * proceeds exactly as it did before this existed.
     */
    private GraphForget planGraphForget(String scope, UUID id, String subject) {
        if (!graphForgetSyncEnabled) {
            return null;
        }
        try {
            GraphMemoryService graph = graphMemoryService.getIfAvailable();
            if (graph == null) {
                return null;
            }
            if ("agent".equals(scope)) {
                AgentMemoryService agent = agentMemoryService.getIfAvailable();
                if (agent == null) {
                    return null;
                }
                List<AgentMemoryEntry> all = agent.listAllForSubject(subject);
                AgentMemoryEntry target = all.stream().filter(m -> id.equals(m.id())).findFirst().orElse(null);
                if (target == null || target.agentId() == null) {
                    return null;
                }
                List<String> others = new ArrayList<>();
                for (AgentMemoryEntry m : all) {
                    if (!id.equals(m.id()) && Objects.equals(m.agentId(), target.agentId())) {
                        others.add(m.content());
                    }
                }
                return new GraphForget(graph, target.agentId(), target.content(), others);
            }
            if ("shared".equals(scope)) {
                TenantMemoryService tenant = tenantMemoryService.getIfAvailable();
                if (tenant == null) {
                    return null;
                }
                List<TenantMemoryEntry> all = tenant.listFactsForSubject(subject);
                TenantMemoryEntry target = all.stream().filter(m -> id.equals(m.id())).findFirst().orElse(null);
                if (target == null) {
                    return null;
                }
                List<String> others = new ArrayList<>();
                for (TenantMemoryEntry m : all) {
                    if (!id.equals(m.id())) {
                        others.add(m.content());
                    }
                }
                return new GraphForget(graph, null, target.content(), others);
            }
            return null;
        } catch (Exception e) {
            log.warn("Graph forget-sync lookup failed - the memory change itself is unaffected", e);
            return null;
        }
    }

    /** Never throws: a graph failure must not fail an edit/delete whose vector row is already changed. */
    private void syncGraphForget(GraphForget plan, String subject, String replacementText) {
        if (plan == null) {
            return;
        }
        try {
            List<String> others = plan.otherTexts();
            if (replacementText != null) {
                if (replacementText.equals(plan.oldContent())) {
                    return;
                }
                // Edges the rewritten text still supports stay valid.
                others = new ArrayList<>(others);
                others.add(replacementText);
            }
            plan.graph().forgetFactText(subject, plan.tierAgentId(), plan.oldContent(), others);
        } catch (Exception e) {
            log.warn("Graph forget-sync failed - the memory change itself is unaffected", e);
        }
    }

    private static String subject(Principal principal) {
        if (principal == null || !StringUtils.hasText(principal.getName())) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Sign in to manage your memory.");
        }
        return principal.getName();
    }

    private void requireSelfService() {
        if (!selfServiceEnabled) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Memory self-service is disabled.");
        }
    }

    private TenantMemoryService blocksService() {
        TenantMemoryService tenant = requireTenant();
        if (!tenant.blocksEnabled()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Memory blocks are disabled.");
        }
        return tenant;
    }

    private TenantMemoryService requireTenant() {
        TenantMemoryService tenant = tenantMemoryService.getIfAvailable();
        if (tenant == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Memory is not available.");
        }
        return tenant;
    }

    private AgentMemoryService requireAgent() {
        AgentMemoryService agent = agentMemoryService.getIfAvailable();
        if (agent == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Memory is not available.");
        }
        return agent;
    }
}
