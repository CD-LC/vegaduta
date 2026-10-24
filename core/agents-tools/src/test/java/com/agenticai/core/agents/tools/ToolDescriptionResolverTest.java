package com.agenticai.core.agents.tools;

import static org.assertj.core.api.Assertions.assertThat;

import com.agenticai.core.prompts.DefaultPromptResolver;
import com.agenticai.core.prompts.InMemoryPromptOverrideStore;
import com.agenticai.core.prompts.PromptCatalog;
import com.agenticai.core.prompts.PromptOverride;
import com.agenticai.core.prompts.PromptProperties;
import com.agenticai.core.prompts.PromptScope;
import com.agenticai.core.prompts.tool.ToolPromptSchemas;
import com.agenticai.core.security.TenantContext;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

/** The decorator that puts registry overrides of tool and parameter texts on a turn's tool list. */
class ToolDescriptionResolverTest {

    private static final UUID AGENT = UUID.fromString("00000000-0000-0000-0000-0000000000a1");

    private static final String SCHEMA =
            "{\"type\":\"object\",\"properties\":{"
                    + "\"query\":{\"type\":\"string\",\"description\":\"What to find.\"},"
                    + "\"maxChars\":{\"type\":\"integer\",\"description\":\"Cap.\"},"
                    + "\"rows\":{\"type\":\"array\",\"items\":{\"type\":\"object\",\"properties\":"
                    + "{\"id\":{\"type\":\"string\",\"description\":\"Row id.\"}}}}"
                    + "},\"required\":[\"query\"]}";

    private static String file(String title, String text, String extra) {
        return "---\ntitle: "
                + title
                + "\nrisk: TOOL_DESCRIPTION\nstrictPlaceholders: false\n"
                + extra
                + "---\n"
                + text
                + "\n";
    }

    private final PromptCatalog catalog =
            PromptCatalog.fromSources(
                    Map.of(
                            "prompts/en/tool.description.find_things.md", file("find", "Finds things.", ""),
                            "prompts/en/tool.param.find_things.query.md", file("q", "What to find.", ""),
                            "prompts/en/tool.param.find_things.max_chars.md", file("m", "Cap.", ""),
                            "prompts/en/tool.param.find_things.rows.id.md", file("r", "Row id.", ""),
                            "prompts/en/tool.description.http_thing.md", file("plain", "Plain variant.", ""),
                            "prompts/en/tool.description.http_thing.safe.md", file("safe", "Safe variant.", ""),
                            "prompts/en/tool.param.http_thing.safe.url.md", file("u", "The url.", ""),
                            "prompts/en/tool.description.templated.md",
                            file("templated", "Members: {roster}.", "placeholders: roster\n"),
                            "prompts/en/tool.param.templated.what.md", file("w", "What.", ""),
                            "prompts/en/tool.template.selfrender.md",
                            file("selfrender", "Self: {roster}.", "placeholders: roster!\n")),
                    "en",
                    4000);

    private final InMemoryPromptOverrideStore store = new InMemoryPromptOverrideStore();
    private final DefaultPromptResolver resolver =
            new DefaultPromptResolver(catalog, store, new PromptProperties());
    private final ToolDescriptionResolver decorator = ToolDescriptionResolver.of(resolver);

    @BeforeEach
    void tenant() {
        TenantContext.set(TenantContext.of("tenant-a", "admin@a", Set.of("ROLE_tenant-admin")));
    }

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    private void override(PromptScope scope, UUID agent, String key, String text) {
        store.put(
                new PromptOverride(
                        scope,
                        scope == PromptScope.AGENT ? agent.toString() : "",
                        key,
                        "en",
                        text,
                        true,
                        1,
                        "tester",
                        Instant.now()));
        resolver.evictAll();
    }

    private static final class Fake implements ToolCallback {
        final String name;
        final String description;
        final String schema;
        final AtomicInteger calls = new AtomicInteger();

        Fake(String name, String description, String schema) {
            this.name = name;
            this.description = description;
            this.schema = schema;
        }

        @Override
        public ToolDefinition getToolDefinition() {
            return ToolDefinition.builder().name(name).description(description).inputSchema(schema).build();
        }

        @Override
        public String call(String toolInput) {
            calls.incrementAndGet();
            return "ok:" + toolInput;
        }

        @Override
        public String call(String toolInput, ToolContext toolContext) {
            calls.incrementAndGet();
            return "ctx:" + toolInput;
        }
    }

    private static Fake find() {
        return new Fake("find_things", "Finds things.", SCHEMA);
    }

    @Test
    void withoutOverridesTheToolIsReturnedAsTheSameObject() {
        Fake tool = find();
        assertThat(decorator.apply(tool, AGENT)).isSameAs(tool);
        assertThat(decorator.apply(List.<ToolCallback>of(tool), AGENT)).containsExactly(tool);
    }

    @Test
    void aToolWithoutKeysPassesThroughUntouched() {
        Fake mcp = new Fake("mcp__server__lookup", "From a server.", "{}");
        override(PromptScope.PLATFORM, null, "tool.description.find_things", "Changed.");
        assertThat(decorator.apply(mcp, AGENT)).isSameAs(mcp);
    }

    @Test
    void anAgentOverrideChangesTheDescriptionForThatAgentOnly() {
        override(PromptScope.AGENT, AGENT, "tool.description.find_things", "Finds things for this agent.");
        ToolCallback forAgent = decorator.apply(find(), AGENT);
        ToolCallback forOther = decorator.apply(find(), UUID.randomUUID());
        ToolCallback noAgent = decorator.apply(find(), null);

        assertThat(forAgent.getToolDefinition().description()).isEqualTo("Finds things for this agent.");
        assertThat(forOther.getToolDefinition().description()).isEqualTo("Finds things.");
        assertThat(noAgent.getToolDefinition().description()).isEqualTo("Finds things.");
    }

    @Test
    void tenantAndPlatformOverridesApplyAndTheAgentWinsOverTheTenantOverThePlatform() {
        override(PromptScope.PLATFORM, null, "tool.description.find_things", "platform");
        assertThat(decorator.apply(find(), AGENT).getToolDefinition().description()).isEqualTo("platform");
        override(PromptScope.TENANT, null, "tool.description.find_things", "tenant");
        assertThat(decorator.apply(find(), AGENT).getToolDefinition().description()).isEqualTo("tenant");
        override(PromptScope.AGENT, AGENT, "tool.description.find_things", "agent");
        assertThat(decorator.apply(find(), AGENT).getToolDefinition().description()).isEqualTo("agent");
    }

    @Test
    void aTenantOverrideDoesNotLeakToAnotherTenant() {
        override(PromptScope.TENANT, null, "tool.description.find_things", "tenant-a wording");
        TenantContext.set(TenantContext.of("tenant-b", "admin@b", Set.of("ROLE_tenant-admin")));
        resolver.evictAll();
        assertThat(decorator.apply(find(), AGENT).getToolDefinition().description()).isEqualTo("Finds things.");
    }

    @Test
    void parameterOverridesChangeOnlyThoseTextsAndKeepTheSchemaShape() {
        override(PromptScope.TENANT, null, "tool.param.find_things.max_chars", "At most this many characters.");
        override(PromptScope.TENANT, null, "tool.param.find_things.rows.id", "Identifier of the row.");

        ToolDefinition def = decorator.apply(find(), AGENT).getToolDefinition();

        assertThat(def.description()).isEqualTo("Finds things.");
        assertThat(ToolPromptSchemas.byPath(def.inputSchema()))
                .containsEntry("query", "What to find.")
                .containsEntry("maxChars", "At most this many characters.")
                .containsEntry("rows.id", "Identifier of the row.");
        assertThat(ToolPromptSchemas.sameShape(SCHEMA, def.inputSchema())).isTrue();
        assertThat(def.name()).isEqualTo("find_things");
        assertThat(def.inputSchema()).contains("\"required\":[\"query\"]");
    }

    @Test
    void theNameAndTheCallsAreTheWrappedToolsOwn() {
        override(PromptScope.TENANT, null, "tool.description.find_things", "New words.");
        Fake tool = find();
        ToolCallback wrapped = decorator.apply(tool, AGENT);

        assertThat(wrapped).isNotSameAs(tool);
        assertThat(wrapped.getToolDefinition().name()).isEqualTo("find_things");
        assertThat(wrapped.call("{\"query\":\"x\"}")).isEqualTo("ok:{\"query\":\"x\"}");
        assertThat(wrapped.call("{}", new ToolContext(Map.of()))).isEqualTo("ctx:{}");
        assertThat(tool.calls).hasValue(2);
        assertThat(wrapped.getToolMetadata()).isEqualTo(tool.getToolMetadata());
    }

    @Test
    void theVariantWhoseShippedTextMatchesTheBuiltTextIsTheOneOverridden() {
        override(PromptScope.TENANT, null, "tool.description.http_thing.safe", "Safe, reworded.");
        Fake plain = new Fake("http_thing", "Plain variant.", "{}");
        Fake safe = new Fake("http_thing", "Safe variant.", "{\"type\":\"object\",\"properties\":{\"url\":{\"type\":\"string\",\"description\":\"The url.\"}}}");

        assertThat(decorator.apply(plain, AGENT)).isSameAs(plain);
        assertThat(decorator.apply(safe, AGENT).getToolDefinition().description()).isEqualTo("Safe, reworded.");

        override(PromptScope.TENANT, null, "tool.param.http_thing.safe.url", "Where to send it.");
        assertThat(ToolPromptSchemas.byPath(decorator.apply(safe, AGENT).getToolDefinition().inputSchema()))
                .containsEntry("url", "Where to send it.");
        // The plain tool has no such parameter: the variant's key never reaches it.
        assertThat(decorator.apply(plain, AGENT)).isSameAs(plain);
    }

    @Test
    void aTemplatedDescriptionIsFilledWithTheValuesTheCodeUsedAndItsParametersStillApply() {
        override(PromptScope.TENANT, null, "tool.description.templated", "Members: {roster}. Be brief.");
        override(PromptScope.TENANT, null, "tool.param.templated.what", "Which one.");
        Fake tool =
                new Fake(
                        "templated",
                        "Members: a, b.",
                        "{\"type\":\"object\",\"properties\":{\"what\":{\"type\":\"string\",\"description\":\"What.\"}}}");

        ToolDefinition def = decorator.apply(tool, AGENT).getToolDefinition();

        assertThat(def.description()).isEqualTo("Members: a, b. Be brief.");
        assertThat(ToolPromptSchemas.byPath(def.inputSchema())).containsEntry("what", "Which one.");
    }

    @Test
    void aSelfRenderingToolsTemplateIsNeverAppliedByTheDecorator() {
        override(PromptScope.TENANT, null, "tool.template.selfrender", "Self: {roster}. Be brief.");
        // Even a built text that fits the shipped template's shape is left alone: the tool renders itself.
        Fake rendered = new Fake("selfrender", "Self: a, b.", "{}");
        assertThat(decorator.apply(rendered, AGENT)).isSameAs(rendered);
    }

    @Test
    void aBlankOverrideIsNoOverride() {
        override(PromptScope.TENANT, null, "tool.description.find_things", "   ");
        Fake tool = find();
        assertThat(decorator.apply(tool, AGENT)).isSameAs(tool);
    }

    @Test
    void aFailingStoreServesTheBuiltWording() {
        store.failWith = new IllegalStateException("db down");
        resolver.evictAll();
        Fake tool = find();
        assertThat(decorator.apply(tool, AGENT).getToolDefinition().description()).isEqualTo("Finds things.");
    }

    @Test
    void toolsWhoseSchemaDoesNotParseKeepTheirSchemaWhenOnlyTheDescriptionIsOverridden() {
        override(PromptScope.TENANT, null, "tool.description.find_things", "Reworded.");
        Fake odd = new Fake("find_things", "Finds things.", "not json");
        ToolDefinition def = decorator.apply(odd, AGENT).getToolDefinition();
        assertThat(def.description()).isEqualTo("Reworded.");
        assertThat(def.inputSchema()).isEqualTo("not json");
    }
}
