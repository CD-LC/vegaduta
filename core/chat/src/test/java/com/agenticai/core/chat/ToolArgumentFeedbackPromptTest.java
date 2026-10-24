package com.agenticai.core.chat;

import static org.assertj.core.api.Assertions.assertThat;

import com.agenticai.core.prompts.DefaultPromptResolver;
import com.agenticai.core.prompts.InMemoryPromptOverrideStore;
import com.agenticai.core.prompts.PromptOverride;
import com.agenticai.core.prompts.PromptProperties;
import com.agenticai.core.prompts.PromptResolver;
import com.agenticai.core.prompts.PromptScope;
import com.agenticai.core.security.TenantContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

/**
 * The "NOT executed" feedback of {@link ToolArgumentValidator} is registry text: byte-identical to the literals it
 * replaced, and an override of the tenant or the agent reaches the real {@link ArgumentValidatingToolCallback} path.
 */
class ToolArgumentFeedbackPromptTest {

    private static final String SCHEMA =
            "{\"type\":\"object\",\"properties\":{\"type\":{\"type\":\"string\"},\"filename\":{\"type\":\"string\"}},\"required\":[\"type\",\"filename\"]}";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final InMemoryPromptOverrideStore store = new InMemoryPromptOverrideStore();
    private final DefaultPromptResolver resolver =
            new DefaultPromptResolver(PromptResolver.defaultsOnly().catalog(), store, new PromptProperties());

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    private static ToolCallback tool() {
        return new ToolCallback() {
            @Override
            public ToolDefinition getToolDefinition() {
                return ToolDefinition.builder().name("generate_document").description("d").inputSchema(SCHEMA).build();
            }

            @Override
            public String call(String toolInput) {
                return "ran";
            }
        };
    }

    @Test
    void theShippedHintAndProblemsAreByteIdenticalToTheLiteralsTheyReplaced() throws Exception {
        String args = "{\"f\":\"x\",\"path\":\"p\"}";
        ToolArgumentValidator.Result result = ToolArgumentValidator.validate(args, SCHEMA, MAPPER);
        String oldHint = "The call to generate_document was NOT executed: missing required property 'type'; "
                + "missing required property 'filename'. These keys are not parameters of generate_document and were "
                + "ignored: f, path. Call generate_document again with arguments that match its schema.";
        JsonNode json = MAPPER.readTree(ToolArgumentValidator.feedback("generate_document", result, SCHEMA, MAPPER));
        assertThat(json.get("hint").asText()).isEqualTo(oldHint);
        assertThat(json.get("error").asText()).isEqualTo("invalid_arguments");

        assertThat(ToolArgumentValidator.validate("{not json", SCHEMA, MAPPER).problems())
                .containsExactly("arguments are not valid JSON");
        assertThat(ToolArgumentValidator.validate("[1]", SCHEMA, MAPPER).problems())
                .containsExactly("arguments must be a JSON object, got array");
        // no unknown keys: no unknown-keys sentence
        ToolArgumentValidator.Result missing = ToolArgumentValidator.validate("{}", SCHEMA, MAPPER);
        assertThat(MAPPER.readTree(ToolArgumentValidator.feedback("t", missing, SCHEMA, MAPPER)).get("hint").asText())
                .isEqualTo("The call to t was NOT executed: missing required property 'type'; missing required property "
                        + "'filename'. Call t again with arguments that match its schema.");
        assertThat(PromptResolver.defaultsOnly().catalog().problems()).isEmpty();
    }

    @Test
    void aTenantAndAnAgentOverrideReachTheCallbackThatRejectsTheCall() throws Exception {
        UUID agent = UUID.randomUUID();
        TenantContext.set(TenantContext.of("t1", "owner", Set.of()));
        store.put(new PromptOverride(PromptScope.TENANT, "", ChatPrompts.INVALID_ARGUMENTS_HINT, "en", "Not run: {problems}. {unknown_keys}Fix {tool}.", true, 1, "owner", Instant.now()));
        store.put(new PromptOverride(PromptScope.TENANT, "", ChatPrompts.INVALID_ARGUMENTS_MISSING_REQUIRED, "en", "need '{property}'", true, 1, "owner", Instant.now()));
        resolver.evictAll();

        List<String> rejected = new java.util.ArrayList<>();
        ArgumentValidatingToolCallback callback = new ArgumentValidatingToolCallback(
                tool(), MAPPER, (name, result) -> rejected.add(name), resolver, new ChatPrompts.Run(null, null));
        JsonNode tenantLevel = MAPPER.readTree(callback.call("{\"f\":1}"));
        assertThat(tenantLevel.get("hint").asText())
                .isEqualTo("Not run: need 'type'; need 'filename'. These keys are not parameters of generate_document and were ignored: f. Fix generate_document.");
        assertThat(tenantLevel.get("problems").get(0).asText()).isEqualTo("need 'type'");

        store.put(new PromptOverride(PromptScope.AGENT, agent.toString(), ChatPrompts.INVALID_ARGUMENTS_HINT, "en", "Agent says no to {tool}: {problems}.", true, 1, "owner", Instant.now()));
        resolver.evictAll();
        ArgumentValidatingToolCallback forAgent = new ArgumentValidatingToolCallback(
                tool(), MAPPER, (name, result) -> rejected.add(name), resolver, new ChatPrompts.Run(agent, null));
        assertThat(MAPPER.readTree(forAgent.call("{}")).get("hint").asText())
                .isEqualTo("Agent says no to generate_document: need 'type'; need 'filename'.");
        assertThat(forAgent.call("{\"type\":\"PDF\",\"filename\":\"a\"}")).isEqualTo("ran");
    }

    @Test
    void theSoftStopFallbackJsonCarriesTheRegistryInstructionAndNoEnglishOfItsOwn() throws Exception {
        String instruction = "Stop \"now\"\n{rounds}";
        JsonNode parsed = MAPPER.readTree(ArgumentSanitizingToolCallingManager.fallbackSoftStop(instruction));
        assertThat(parsed.get("error").asText()).isEqualTo(ArgumentSanitizingToolCallingManager.TOOL_BUDGET_EXHAUSTED);
        assertThat(parsed.get("result").asText()).isEqualTo(instruction);
    }
}
