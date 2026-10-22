package com.agenticai.core.templates;

import static org.assertj.core.api.Assertions.assertThat;

import com.agenticai.core.agents.core.Agent;
import com.agenticai.core.agents.core.ReasoningDepth;
import com.agenticai.core.agents.core.TuningMode;
import com.agenticai.core.agents.dto.AgentCreateRequest;
import com.agenticai.core.providers.ProviderName;
import com.agenticai.core.security.ExperienceMode;
import com.agenticai.core.workflow.WorkflowDefinition;
import com.agenticai.core.workflow.WorkflowStatus;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * THE acceptance test for this module's single most important correctness property: the {@code
 * config_json} snapshot is an ALLOW-LIST that carries only safe design fields and strips every
 * secret / identity / tenant-scoped value. Plain JUnit5, no Spring context, same style as {@code
 * ResourceAccessServiceTest} in the sibling module.
 */
class TemplateSnapshotServiceTest {

    private final ObjectMapper mapper = com.agenticai.core.json.JsonMappers.plain();
    private final TemplateSnapshotService service = new TemplateSnapshotService(mapper);

    // Sentinel secrets/identity/tenant values that MUST NEVER appear in a snapshot.
    private static final String SECRET_CREATED_BY = "auth0|super-secret-owner-subject-9987";
    private static final String SECRET_TENANT_ID = "tenant-DEADBEEF-do-not-leak";
    private static final String SECRET_PROVIDER_KEY = "sk-ant-REALPROVIDERKEY-must-not-leak";
    private static final String SECRET_CHANNEL_TOKEN = "whatsapp-channel-secret-do-not-leak";
    private static final UUID SECRET_COLLECTION_ID =
            UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID SECRET_COLLABORATOR_ID =
            UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final String SECRET_REPO_ID = "tenant-private-repo-do-not-leak";

    @Test
    void agentSnapshot_stripsSecretsIdentityAndTenantScopedFields() {
        Agent agent = fullyPopulatedAgent();
        String configJson = service.snapshotAgent(agent);

        // ---- the security property: none of these ever enter the snapshot ----
        assertThat(configJson)
                .doesNotContain(SECRET_CREATED_BY)
                .doesNotContain(SECRET_TENANT_ID)
                .doesNotContain(SECRET_PROVIDER_KEY)
                .doesNotContain(SECRET_CHANNEL_TOKEN)
                .doesNotContain(SECRET_COLLECTION_ID.toString())
                .doesNotContain(SECRET_COLLABORATOR_ID.toString())
                .doesNotContain(SECRET_REPO_ID);

        // ---- the allow-list: only safe design keys are present ----
        JsonNode config = parse(configJson);
        List<String> keys = fieldNames(config);
        assertThat(keys)
                // identity / instance / tenant-scoped fields are excluded by name
                .doesNotContain(
                        "createdBy",
                        "id",
                        "createdAt",
                        "updatedAt",
                        "allowedCodeIntelRepoIds",
                        "collaboratorAgentIds",
                        "sourceTenantId",
                        "tenantId")
                // safe design fields are present
                .contains(
                        "systemPrompt",
                        "providerName",
                        "model",
                        "allowedToolNames",
                        "enabledGuardrails",
                        "reasoningDepth");

        // allowedKnowledgeCollectionIds: the KEY is retained (renderer compatibility)
        // but the tenant-scoped UUIDs are stripped - the value is always an empty array.
        assertThat(config.get("allowedKnowledgeCollectionIds").isArray()).isTrue();
        assertThat(config.get("allowedKnowledgeCollectionIds")).isEmpty();

        // The design content the user deliberately authored IS carried through.
        assertThat(config.get("systemPrompt").asString())
                .isEqualTo("You are a helpful returns assistant.");
        assertThat(config.get("model").asString()).isEqualTo("claude-sonnet-4-5");
        assertThat(config.get("providerName").asString()).isEqualTo("ANTHROPIC");
        // An unconfigured optional design field is present-but-null (Jackson emits the
        // record component), never omitted and never defaulted to something misleading.
        assertThat(config.hasNonNull("economyModel")).isFalse();
        assertThat(config.hasNonNull("economyProviderName")).isFalse();
    }

    @Test
    void agentSnapshot_roundTripsBackIntoACreateRequestUsingOnlySafeFields() {
        Agent agent = fullyPopulatedAgent();
        String configJson = service.snapshotAgent(agent);

        AgentCreateRequest request =
                service.toAgentCreateRequest(configJson, "Imported Returns Bot");

        assertThat(request.name()).isEqualTo("Imported Returns Bot");
        assertThat(request.systemPrompt()).isEqualTo("You are a helpful returns assistant.");
        assertThat(request.providerName()).isEqualTo(ProviderName.ANTHROPIC);
        assertThat(request.model()).isEqualTo("claude-sonnet-4-5");
        assertThat(request.reasoningDepth()).isEqualTo(ReasoningDepth.FAST);
        assertThat(request.tuningMode()).isEqualTo(TuningMode.MANUAL);
    }

    @Test
    void workflowSnapshot_carriesOnlyTheGraphNotTheWebhookSecretOrOwner() throws Exception {
        String graphJson = "{\"nodes\":[{\"id\":\"n1\",\"type\":\"agent\"}],\"edges\":[]}";
        WorkflowDefinition definition =
                new WorkflowDefinition(
                        "Order triage", "Triages inbound orders", graphJson, WorkflowStatus.ACTIVE);
        // Live per-instance secrets that must NOT be snapshotted. The webhook
        // token/hash used to live on this entity but moved to the per-node
        // WorkflowTriggerNode (see WorkflowDefinition's per-node-triggers
        // refactor) - snapshotWorkflow() never had access to that entity, so
        // there's nothing to mint here anymore; createdBy is still the
        // relevant identity field to guard.
        setField(definition, "createdBy", SECRET_CREATED_BY);

        String configJson = service.snapshotWorkflow(definition);

        assertThat(configJson)
                .doesNotContain(SECRET_CHANNEL_TOKEN)
                .doesNotContain("hash-should-not-leak")
                .doesNotContain(SECRET_CREATED_BY);

        JsonNode config = parse(configJson);
        assertThat(fieldNames(config)).containsExactlyInAnyOrder("name", "description", "graph");
        // The design graph itself is preserved intact.
        assertThat(config.get("graph").get("nodes").get(0).get("id").asString()).isEqualTo("n1");
    }

    // ------------------------------------------------------------------

    private Agent fullyPopulatedAgent() {
        Agent agent =
                new Agent(
                        "Returns Bot",
                        "You are a helpful returns assistant.",
                        ProviderName.ANTHROPIC,
                        "claude-sonnet-4-5",
                        2048,
                        0.4,
                        0.9,
                        true,
                        ExperienceMode.ADVANCED,
                        ReasoningDepth.FAST,
                        TuningMode.MANUAL);
        // Identity + tenant-scoped fields that MUST be stripped:
        agent.setCreatedBy(SECRET_CREATED_BY);
        agent.setAllowedKnowledgeCollectionIds(Set.of(SECRET_COLLECTION_ID));
        agent.setAllowedCodeIntelRepoIds(Set.of(SECRET_REPO_ID));
        agent.setCollaboratorAgentIds(Set.of(SECRET_COLLABORATOR_ID));
        // Safe design fields that SHOULD carry through:
        agent.setAllowedToolNames(Set.of("web_search", "mcp:slack"));
        agent.setEnabledGuardrails(Set.of("pii", "jailbreak"));
        agent.setSarvamEnabled(true);
        return agent;
    }

    private JsonNode parse(String json) {
        try {
            return mapper.readTree(json);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static List<String> fieldNames(JsonNode node) {
        List<String> names = new java.util.ArrayList<>();
        node.propertyNames().iterator().forEachRemaining(names::add);
        return names;
    }

    private static void setField(Object target, String field, Object value) throws Exception {
        var f = target.getClass().getDeclaredField(field);
        f.setAccessible(true);
        f.set(target, value);
    }
}
