package com.agenticai.core.templates;

import com.agenticai.core.agents.core.Agent;
import com.agenticai.core.agents.core.ReasoningDepth;
import com.agenticai.core.agents.core.TuningMode;
import com.agenticai.core.agents.dto.AgentCreateRequest;
import com.agenticai.core.providers.ProviderName;
import com.agenticai.core.security.ExperienceMode;
import com.agenticai.core.workflow.WorkflowDefinition;
import com.agenticai.core.workflow.dto.WorkflowGraphDto;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * THE security boundary of this module. Builds a template's {@code config_json}
 * as an <b>ALLOW-LIST</b> snapshot of an agent's/workflow's design - it copies
 * ONLY the known-safe fields enumerated below, one by one, off the source
 * entity. It never serialises the whole entity, never reads a credential/
 * provider-key/channel-secret table, and never touches tenant identity.
 *
 * <p><b>What is deliberately EXCLUDED, and why</b> (deny-by-default: a field not
 * named in a {@code snapshotXxx} method below simply never enters the snapshot):
 * <ul>
 *   <li>{@code Agent.createdBy} / {@code WorkflowDefinition.createdBy} - the
 *       submitter's Keycloak subject (identity/PII).</li>
 *   <li>{@code id}, {@code createdAt}, {@code updatedAt} - instance metadata that
 *       means nothing in an importing tenant.</li>
 *   <li>{@code allowedKnowledgeCollectionIds}, {@code allowedCodeIntelRepoIds},
 *       {@code collaboratorAgentIds} - tenant-scoped UUIDs / repo ids that point
 *       at the SOURCE tenant's private resources and would be meaningless (or a
 *       cross-tenant reference leak) in another tenant.</li>
 *   <li>fallback telemetry counters, {@code lastFallbackAt} - runtime stats.</li>
 *   <li>Provider API keys, channel secrets, tenant ids, knowledge documents - these
 *       are NOT columns on {@code Agent}/{@code WorkflowDefinition} at all (they live
 *       in separate credential/channel tables this service never reads), so they
 *       cannot enter a snapshot built field-by-field from the entity. The security
 *       test asserts this holds even for a fully-populated agent.</li>
 * </ul>
 *
 * <p><b>Workflow graph:</b> {@code WorkflowDefinition.graphJson} (the React-Flow
 * {@code {nodes, edges}} design document) is copied; the encrypted webhook token,
 * schedule, trigger-channel binding, and {@code createdBy} columns are NOT - those
 * are live per-instance integrations/secrets, not design.
 */
@Service
public class TemplateSnapshotService {

    private static final Logger log = LoggerFactory.getLogger(TemplateSnapshotService.class);

    private final ObjectMapper objectMapper;

    public TemplateSnapshotService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    // ---- Snapshot record shapes (all fields are safe design fields by construction) ----

    /** The allow-listed agent design. Every field here is a design choice, never a secret/identity/tenant value. */
    public record AgentSnapshot(
        String systemPrompt,
        String providerName,
        String model,
        int maxTokens,
        double temperature,
        double topP,
        String tuningMode,
        String reasoningDepth,
        String autonomyTier,
        String experienceMode,
        boolean knowledgeBaseEnabled,
        boolean sarvamEnabled,
        boolean e2bEnabled,
        boolean codeWorkspaceEnabled,
        boolean responseCachingEnabled,
        boolean semanticResponseCachingEnabled,
        boolean shareMemoryWithTenant,
        boolean costRoutingEnabled,
        String economyProviderName,
        String economyModel,
        String fallbackProviderName,
        String fallbackModel,
        String voiceModelProvider,
        String voiceModel,
        String videoModelProvider,
        String videoModel,
        List<String> allowedToolNames,
        List<String> enabledGuardrails,
        /**
         * Key retained for the admin/gallery renderer's compatibility (it keys off
         * this exact name from {@code AgentResponse}), but ALWAYS emitted EMPTY: the
         * source agent's knowledge-collection ids are tenant-scoped UUIDs that point
         * at the SOURCE tenant's private KB and are meaningless (and a minor
         * cross-tenant reference leak) anywhere else. The design intent "restricted
         * to specific collections" doesn't survive a cross-tenant import, so the
         * safe, honest value is an empty list. See the security test.
         */
        List<String> allowedKnowledgeCollectionIds
    ) {
    }

    /** The allow-listed workflow design: the graph document plus its human-facing name/description. */
    public record WorkflowSnapshot(
        String name,
        String description,
        JsonNode graph
    ) {
    }

    // ---- Build config_json from a source entity (the sanitising step) ----

    /**
     * Serialise the allow-listed {@link AgentSnapshot} of {@code agent} to the
     * template's {@code config_json}. Reading only the getters named here is what
     * makes the snapshot safe - do not replace this with a reflective/whole-entity
     * serialisation.
     */
    public String snapshotAgent(Agent agent) {
        AgentSnapshot snapshot = new AgentSnapshot(
            agent.getSystemPrompt(),
            nameOf(agent.getProviderName()),
            agent.getModel(),
            agent.getMaxTokens(),
            agent.getTemperature(),
            agent.getTopP(),
            nameOf(agent.getTuningMode()),
            nameOf(agent.getReasoningDepth()),
            nameOf(agent.getAutonomyTier()),
            nameOf(agent.getExperienceMode()),
            agent.isKnowledgeBaseEnabled(),
            agent.isSarvamEnabled(),
            agent.isE2bEnabled(),
            agent.isCodeWorkspaceEnabled(),
            agent.isResponseCachingEnabled(),
            agent.isSemanticResponseCachingEnabled(),
            agent.isShareMemoryWithTenant(),
            agent.isCostRoutingEnabled(),
            nameOf(agent.getEconomyProviderName()),
            agent.getEconomyModel(),
            nameOf(agent.getFallbackProviderName()),
            agent.getFallbackModel(),
            nameOf(agent.getVoiceModelProvider()),
            agent.getVoiceModel(),
            nameOf(agent.getVideoModelProvider()),
            agent.getVideoModel(),
            new ArrayList<>(agent.getAllowedToolNames()),
            new ArrayList<>(agent.getEnabledGuardrails()),
            // Deliberately EMPTY - see the field's javadoc: the key is kept for the
            // renderer, the tenant-scoped collection UUIDs are stripped.
            List.of()
        );
        return write(snapshot);
    }

    /**
     * Serialise the allow-listed {@link WorkflowSnapshot} of {@code definition}.
     * Only {@code name}, {@code description} and the parsed {@code graphJson} graph
     * are copied - never the webhook token, schedule, trigger binding, or createdBy.
     */
    public String snapshotWorkflow(WorkflowDefinition definition) {
        JsonNode graph;
        try {
            graph = objectMapper.readTree(definition.getGraphJson() == null ? "{}" : definition.getGraphJson());
        } catch (Exception e) {
            throw new IllegalArgumentException("Could not read the workflow graph to snapshot it: " + e.getMessage(), e);
        }
        return write(new WorkflowSnapshot(definition.getName(), definition.getDescription(), graph));
    }

    // ---- Reconstruct a create request from config_json (the import step) ----

    /**
     * Build an {@link AgentCreateRequest} from a stored agent snapshot, so import
     * goes through {@code AgentService.create} (tenant caps, FREE-tier provider
     * coercion, edit-lock window, ownership stamping) rather than a raw insert.
     * {@code newName} is the importer-chosen name (the template title). Only the
     * fields {@code AgentCreateRequest} accepts are carried through here; the richer
     * design fields in the snapshot (guardrails, tool allow-list, voice/video, etc.)
     * are preserved in {@code config_json} for display but are not settable via the
     * public create request, and are intentionally not back-doored in.
     */
    public AgentCreateRequest toAgentCreateRequest(String configJson, String newName) {
        AgentSnapshot s = read(configJson, AgentSnapshot.class);
        return new AgentCreateRequest(
            newName,
            s.systemPrompt(),
            parseEnum(ProviderName.class, s.providerName(), ProviderName.PLATFORM_HOSTED),
            s.model(),
            s.maxTokens() > 0 ? s.maxTokens() : null,
            s.temperature(),
            s.topP(),
            s.knowledgeBaseEnabled(),
            parseEnumNullable(ExperienceMode.class, s.experienceMode()),
            parseEnumNullable(ReasoningDepth.class, s.reasoningDepth()),
            parseEnumNullable(TuningMode.class, s.tuningMode()),
            // edgeRoutingPolicy - deliberately NOT imported from a template
            // snapshot: a routing policy is a tenant governance/compliance
            // decision about THIS deployment, not a portable agent design
            // trait, same "not back-doored in" reasoning as the fields below.
            null,
            // voiceModelProvider/voiceModel/videoModelProvider/videoModel -
            // null means "inherit the default fallback chain" (same as every
            // other AgentCreateRequest caller that doesn't set these); a
            // template import doesn't carry per-provider voice/video/tts
            // config through config_json, same "not back-doored in" reasoning
            // this method's own javadoc already states for the richer design
            // fields.
            null, null, null, null, null, null
        );
    }

    /** Parse the workflow graph back out of a stored snapshot for {@code WorkflowService.create}. */
    public WorkflowGraphDto toWorkflowGraph(String configJson) {
        WorkflowSnapshot s = read(configJson, WorkflowSnapshot.class);
        try {
            return objectMapper.treeToValue(s.graph() == null ? objectMapper.createObjectNode() : s.graph(), WorkflowGraphDto.class);
        } catch (Exception e) {
            throw new IllegalArgumentException("Stored template workflow graph is not a valid graph: " + e.getMessage(), e);
        }
    }

    public WorkflowSnapshot readWorkflowSnapshot(String configJson) {
        return read(configJson, WorkflowSnapshot.class);
    }

    /**
     * The agent counterpart of {@link #readWorkflowSnapshot}, for the fields
     * {@link #toAgentCreateRequest} deliberately does not carry.
     *
     * <p>{@code toAgentCreateRequest}'s javadoc is right that guardrails and
     * the tool allow-list must not be back-doored into
     * {@code AgentCreateRequest} - they are not creation parameters. But
     * "not settable at create time" was being read as "dropped on import",
     * which quietly made every tool-using template import as a chatbot with
     * no tools. The fix is to expose the snapshot so
     * {@code CommunityTemplateService} can apply those fields <i>after</i>
     * create through the same public services the UI uses
     * ({@code AgentService#setAllowedToolNames},
     * {@code AgentGuardrailService#updateAgentGuardrails}), where the plan
     * checks and access checks still run.
     */
    public AgentSnapshot readAgentSnapshot(String configJson) {
        return read(configJson, AgentSnapshot.class);
    }

    /** Parse a stored tags JSON-array string into a list; tolerant of null/blank/garbage (returns empty). */
    public List<String> parseTags(String tagsJson) {
        if (tagsJson == null || tagsJson.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(tagsJson, new TypeReference<List<String>>() {
            });
        } catch (Exception e) {
            log.debug("Ignoring unparseable template tags '{}'", tagsJson);
            return List.of();
        }
    }

    /** Serialise a tag list to its stored JSON-array string ({@code null} for an empty/absent list). */
    public String writeTags(List<String> tags) {
        if (tags == null || tags.isEmpty()) {
            return null;
        }
        return write(tags);
    }

    // ------------------------------------------------------------------

    private static String nameOf(Enum<?> value) {
        return value == null ? null : value.name();
    }

    private static <E extends Enum<E>> E parseEnum(Class<E> type, String value, E fallback) {
        E parsed = parseEnumNullable(type, value);
        return parsed != null ? parsed : fallback;
    }

    private static <E extends Enum<E>> E parseEnumNullable(Class<E> type, String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Enum.valueOf(type, value);
        } catch (IllegalArgumentException e) {
            // Snapshot came from a possibly newer/older version whose enum value no
            // longer exists here - degrade to "unset" rather than failing the import.
            return null;
        }
    }

    private String write(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("Could not serialise template snapshot: " + e.getMessage(), e);
        }
    }

    private <T> T read(String json, Class<T> type) {
        try {
            return objectMapper.readValue(json, type);
        } catch (Exception e) {
            throw new IllegalArgumentException("Template config is not a valid " + type.getSimpleName() + ": " + e.getMessage(), e);
        }
    }
}
