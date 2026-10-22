package com.agenticai.core.agents.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.agenticai.core.providers.ProviderName;
import com.agenticai.core.security.ExperienceMode;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

class AgentSchemaServiceTest {

    private AgentSchemaService service;
    private ObjectMapper mapper;

    @BeforeEach
    void setUp() {
        mapper = com.agenticai.core.json.JsonMappers.plain();
        service = new AgentSchemaService(mapper);
    }

    private Agent agentWith(
            boolean knowledgeBaseEnabled, Set<UUID> allowedCollections, Set<String> allowedTools) {
        Agent agent =
                new Agent(
                        "Test Agent",
                        "You are helpful.",
                        ProviderName.ANTHROPIC,
                        "claude-sonnet-5",
                        1024,
                        0.7,
                        1.0,
                        knowledgeBaseEnabled,
                        ExperienceMode.ADVANCED);
        agent.setAllowedKnowledgeCollectionIds(allowedCollections);
        agent.setAllowedToolNames(allowedTools);
        return agent;
    }

    @Test
    void inputSchemaAlwaysRequiresQueryAndForbidsExtraProperties() throws Exception {
        Agent agent = agentWith(false, Set.of(), Set.of());
        JsonNode schema = mapper.readTree(service.generateInputSchema(agent));

        assertEquals("object", schema.get("type").asString());
        assertFalse(schema.get("additionalProperties").asBoolean());
        assertTrue(schema.get("properties").has("query"));
        assertTrue(schema.get("properties").has("context_data"));
        assertTrue(schema.get("properties").has("approval_required"));

        boolean queryRequired = false;
        for (JsonNode required : schema.get("required")) {
            if ("query".equals(required.asString())) {
                queryRequired = true;
            }
        }
        assertTrue(queryRequired, "query must always be required");
    }

    @Test
    void inputSchemaOmitsKnowledgeCollectionIdsWhenAgentCannotSearchKnowledge() throws Exception {
        Agent agent = agentWith(false, Set.of(), Set.of());
        JsonNode schema = mapper.readTree(service.generateInputSchema(agent));

        assertFalse(
                schema.get("properties").has("knowledge_collection_ids"),
                "an agent with no knowledge access shouldn't advertise a field it can't use");
    }

    @Test
    void inputSchemaIncludesKnowledgeCollectionIdsWhenKnowledgeBaseEnabled() throws Exception {
        Agent agent = agentWith(true, Set.of(), Set.of());
        JsonNode schema = mapper.readTree(service.generateInputSchema(agent));

        assertTrue(schema.get("properties").has("knowledge_collection_ids"));
    }

    @Test
    void inputSchemaIncludesKnowledgeCollectionIdsWhenAllowListNonEmptyEvenIfFlagOff()
            throws Exception {
        Agent agent = agentWith(false, Set.of(UUID.randomUUID()), Set.of());
        JsonNode schema = mapper.readTree(service.generateInputSchema(agent));

        assertTrue(schema.get("properties").has("knowledge_collection_ids"));
    }

    @Test
    void outputSchemaDescribesFixedShapeRegardlessOfAgentConfig() throws Exception {
        Agent agent = agentWith(true, Set.of(), Set.of("some_tool"));
        JsonNode schema = mapper.readTree(service.generateOutputSchema(agent));

        JsonNode properties = schema.get("properties");
        assertTrue(properties.has("result_text"));
        assertTrue(properties.has("confidence"));
        assertTrue(properties.has("sources"));
        assertTrue(properties.has("tool_calls"));
        assertTrue(properties.has("metadata"));
    }

    @Test
    void refreshSetsBothSchemasAndBumpsVersion() {
        Agent agent = agentWith(true, Set.of(), Set.of());
        assertEquals(0, agent.getSchemaVersion());

        service.refresh(agent);

        assertNotNull(agent.getInputSchemaJson());
        assertNotNull(agent.getOutputSchemaJson());
        assertEquals(1, agent.getSchemaVersion());

        service.refresh(agent);
        assertEquals(2, agent.getSchemaVersion());
    }
}
