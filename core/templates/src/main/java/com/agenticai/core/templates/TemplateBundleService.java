package com.agenticai.core.templates;

import com.agenticai.core.agents.core.Agent;
import com.agenticai.core.agents.core.AgentAutonomyTier;
import com.agenticai.core.agents.core.AgentService;
import com.agenticai.core.agents.guardrail.AgentGuardrailService;
import com.agenticai.core.audit.AuditLogService;
import com.agenticai.core.audit.AuditOutcome;
import com.agenticai.core.security.TenantContext;
import com.agenticai.core.templates.dto.AgentBundle;
import com.agenticai.core.templates.dto.AgentBundlePreview;
import com.agenticai.core.templates.dto.TemplateImportResponse;
import com.agenticai.core.workflow.WorkflowService;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Export an agent as a shareable file and import one. A bundle is untrusted input from another
 * person, so import is strict: the format must match, the size is capped, the design must parse as
 * the allow-listed snapshot, a bundle can never ask for {@code FULLY_AUTONOMOUS} (it is lowered to
 * {@code SUPERVISED}, so every side effect waits for approval until the importer changes it) and
 * knowledge-base ids are dropped. The agent is then created through {@code AgentService} like any
 * template import, so plan limits and ownership still apply.
 */
@Service
public class TemplateBundleService {

    public static final String FORMAT = "vegaduta.agent-bundle/1";
    static final int MAX_AGENT_JSON_CHARS = 64_000;
    static final int MAX_TOOLS = 100;

    private final TemplateSnapshotService snapshotService;
    private final TemplateReadinessService readinessService;
    private final CommunityTemplateService templates;
    private final TemplateInstantiator instantiator;
    private final TemplateImportRateLimiter importRateLimiter;
    private final AuditLogService auditLogService;
    private final ObjectMapper objectMapper;

    public TemplateBundleService(
            TemplateSnapshotService snapshotService,
            TemplateReadinessService readinessService,
            AgentService agentService,
            CommunityTemplateService templates,
            AgentGuardrailService agentGuardrailService,
            WorkflowService workflowService,
            TemplateImportRateLimiter importRateLimiter,
            AuditLogService auditLogService,
            ObjectMapper objectMapper) {
        this.snapshotService = snapshotService;
        this.readinessService = readinessService;
        this.templates = templates;
        this.instantiator =
                new TemplateInstantiator(
                        snapshotService, agentService, agentGuardrailService, workflowService);
        this.importRateLimiter = importRateLimiter;
        this.auditLogService = auditLogService;
        this.objectMapper = objectMapper;
    }

    /** The caller's own agent as a bundle (same allow-list snapshot a template uses). */
    @Transactional(readOnly = true)
    public AgentBundle export(UUID agentId) {
        // VIEWER access / same-tenant existence, else 404 (the same check a template snapshot
        // uses).
        Agent agent = templates.loadOwnedAgentEntity(agentId);
        try {
            JsonNode design = objectMapper.readTree(snapshotService.snapshotAgent(agent));
            return new AgentBundle(FORMAT, agent.getName(), null, null, List.of(), design);
        } catch (Exception e) {
            throw new IllegalStateException("Could not build the bundle: " + e.getMessage(), e);
        }
    }

    /** What importing would do. Creates nothing. */
    @Transactional(readOnly = true)
    public AgentBundlePreview preview(AgentBundle bundle) {
        return prepare(bundle).preview();
    }

    @Transactional
    public TemplateImportResponse importBundle(AgentBundle bundle) {
        String subject = TemplateAccess.requireSubject();
        importRateLimiter.assertWithinDailyCap(subject);
        Prepared prepared = prepare(bundle);
        String tenantId = TenantContext.current().map(TenantContext::getTenantId).orElse(null);
        CommunityTemplate transient_ =
                new CommunityTemplate(
                        TemplateKind.AGENT,
                        bundle.title(),
                        bundle.summary(),
                        bundle.category(),
                        snapshotService.writeTags(bundle.tags()),
                        prepared.configJson(),
                        tenantId,
                        subject);
        UUID newId = instantiator.instantiate(transient_);
        auditLogService.record(
                subject,
                "agent.bundle.import",
                "agent",
                newId.toString(),
                AuditOutcome.SUCCESS,
                "Imported agent bundle '"
                        + bundle.title()
                        + "' with tier "
                        + prepared.preview().autonomyTier()
                        + (prepared.preview().adjustments().isEmpty()
                                ? ""
                                : "; adjusted: "
                                        + String.join("; ", prepared.preview().adjustments())));
        return new TemplateImportResponse(TemplateKind.AGENT, newId);
    }

    private Prepared prepare(AgentBundle bundle) {
        if (!FORMAT.equals(bundle.format())) {
            throw new IllegalArgumentException(
                    "Unsupported bundle format '"
                            + bundle.format()
                            + "'; this server reads "
                            + FORMAT
                            + ".");
        }
        if (!bundle.agent().isObject()) {
            throw new IllegalArgumentException("'agent' must be a JSON object.");
        }
        ObjectNode agent = ((ObjectNode) bundle.agent()).deepCopy();
        String raw = agent.toString();
        if (raw.length() > MAX_AGENT_JSON_CHARS) {
            throw new IllegalArgumentException(
                    "The agent design is larger than " + MAX_AGENT_JSON_CHARS + " characters.");
        }
        List<String> adjustments = new ArrayList<>();
        // Never carry another tenant's knowledge-base ids.
        if (agent.hasNonNull("allowedKnowledgeCollectionIds")
                && !agent.get("allowedKnowledgeCollectionIds").isEmpty()) {
            adjustments.add(
                    "knowledge-base restrictions dropped (they point at the author's data)");
        }
        agent.putArray("allowedKnowledgeCollectionIds");
        String tier = agent.path("autonomyTier").asString("");
        if (tier.isBlank()) {
            tier = AgentAutonomyTier.SUPERVISED.name();
            agent.put("autonomyTier", tier);
        } else if (AgentAutonomyTier.FULLY_AUTONOMOUS.name().equals(tier)) {
            adjustments.add(
                    "FULLY_AUTONOMOUS lowered to SUPERVISED: a shared file cannot grant full autonomy");
            tier = AgentAutonomyTier.SUPERVISED.name();
            agent.put("autonomyTier", tier);
        }
        String configJson = agent.toString();
        TemplateSnapshotService.AgentSnapshot snapshot;
        try {
            snapshot = snapshotService.readAgentSnapshot(configJson);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(
                    "The agent design is not valid: " + e.getMessage(), e);
        }
        if (snapshot.systemPrompt() == null || snapshot.systemPrompt().isBlank()) {
            throw new IllegalArgumentException("The agent has no system prompt.");
        }
        List<String> tools =
                snapshot.allowedToolNames() == null ? List.of() : snapshot.allowedToolNames();
        if (tools.size() > MAX_TOOLS) {
            throw new IllegalArgumentException(
                    "The agent lists more than " + MAX_TOOLS + " tools.");
        }
        if (tools.isEmpty()) {
            adjustments.add("no tool list: the agent can use every tool your workspace allows");
        }
        var readiness =
                readinessService.assess(
                        TemplateKind.AGENT,
                        bundle.title(),
                        bundle.summary(),
                        bundle.tags() == null ? List.of() : bundle.tags(),
                        bundle.category(),
                        agent);
        return new Prepared(
                configJson,
                new AgentBundlePreview(
                        bundle.title(), tools, tier, List.copyOf(adjustments), readiness));
    }

    private record Prepared(String configJson, AgentBundlePreview preview) {}
}
