package com.agenticai.core.prompts.tool;

import static org.assertj.core.api.Assertions.assertThat;

import com.agenticai.core.prompts.DefaultPromptResolver;
import com.agenticai.core.prompts.InMemoryPromptOverrideStore;
import com.agenticai.core.prompts.PromptCatalog;
import com.agenticai.core.prompts.PromptOverride;
import com.agenticai.core.prompts.PromptProperties;
import com.agenticai.core.prompts.PromptScope;
import com.agenticai.core.security.TenantContext;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ToolPromptTextsTest {

    private static final UUID AGENT = UUID.fromString("00000000-0000-0000-0000-0000000000a1");

    private static String file(String text, String extra) {
        return "---\ntitle: t\nrisk: TOOL_DESCRIPTION\n" + extra + "---\n" + text + "\n";
    }

    private final PromptCatalog catalog =
            PromptCatalog.fromSources(
                    Map.of(
                            "prompts/en/tool.description.plain.md", file("Plain text.", "strictPlaceholders: false\n"),
                            "prompts/en/tool.param.plain.limit.md", file("The limit.", "strictPlaceholders: false\n"),
                            "prompts/en/tool.description.roster.md",
                            file("Members: {roster}. Hop {hop} of {max}, max {max}.", "placeholders: roster!, hop, max\n"),
                            "prompts/en/tool.template.selfrender.md",
                            file("Self: {roster}. Hop {hop} of {max}.", "placeholders: roster!, hop, max\n"),
                            "prompts/en/tool.description.shell.md", file("Runs.{env} Done.", "placeholders: env\n"),
                            "prompts/en/tool.description.shell.quiet.md", file("Runs quietly.{env} Done.", "placeholders: env\n"),
                            "prompts/en/tool.param.shell.cmd.md", file("The command.", "strictPlaceholders: false\n")),
                    "en",
                    4000);
    private final InMemoryPromptOverrideStore store = new InMemoryPromptOverrideStore();
    private final DefaultPromptResolver resolver = new DefaultPromptResolver(catalog, store, new PromptProperties());

    @BeforeEach
    void tenant() {
        TenantContext.set(TenantContext.of("tenant-a", "admin@a", Set.of("ROLE_tenant-admin")));
    }

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    private void override(PromptScope scope, String key, String text) {
        store.put(new PromptOverride(scope, "", key, "en", text, true, 1, "t", Instant.now()));
        resolver.evictAll();
    }

    @Test
    void aTemplateIsRecoveredFromTheTextTheCodeBuiltAndTheOverrideIsFilledWithTheSameValues() {
        override(PromptScope.TENANT, "tool.description.roster", "Team: {roster}. Be brief ({hop}/{max}).");
        ToolPromptTexts texts = ToolPromptTexts.of(resolver, AGENT);

        String built = "Members: a (A), b (B). Hop 1 of 3, max 3.";

        assertThat(texts.overriddenDescription("roster", built)).isEqualTo("Team: a (A), b (B). Be brief (1/3).");
    }

    @Test
    void aBuiltTextThatIsNotTheShippedTemplateIsNotTouched() {
        override(PromptScope.TENANT, "tool.description.roster", "Team: {roster}.");
        ToolPromptTexts texts = ToolPromptTexts.of(resolver, AGENT);

        assertThat(texts.overriddenDescription("roster", "Something else entirely.")).isNull();
        // A placeholder that occurs twice must have the same value both times.
        assertThat(texts.overriddenDescription("roster", "Members: a. Hop 1 of 3, max 4.")).isNull();
    }

    @Test
    void withoutAnOverrideNothingIsReportedAsOverridden() {
        ToolPromptTexts texts = ToolPromptTexts.of(resolver, AGENT);
        assertThat(texts.overriddenDescription("plain", "Plain text.")).isNull();
        assertThat(texts.overriddenDescription("roster", "Members: a. Hop 1 of 3, max 3.")).isNull();
        assertThat(texts.schema("plain", "{\"type\":\"object\",\"properties\":{\"limit\":{\"type\":\"integer\",\"description\":\"The limit.\"}}}"))
                .contains("The limit.");
    }

    @Test
    void descriptionFillsTheShippedTemplateAndAnOverride() {
        ToolPromptTexts texts = ToolPromptTexts.of(resolver, AGENT);
        Map<String, String> vars = Map.of("roster", "a, b", "hop", "2", "max", "5");
        assertThat(texts.description("selfrender", vars)).isEqualTo("Self: a, b. Hop 2 of 5.");

        override(PromptScope.PLATFORM, "tool.template.selfrender", "Roster {roster}.");
        assertThat(texts.description("selfrender", vars)).isEqualTo("Roster a, b.");
    }

    @Test
    void theParametersOfASelfRenderingToolAreFoundThroughItsTemplateId() {
        override(PromptScope.TENANT, "tool.param.selfrender.what", "Which one.");
        String schema = "{\"type\":\"object\",\"properties\":{\"what\":{\"type\":\"string\",\"description\":\"What.\"}}}";
        // no tool.param.selfrender.* key is shipped, so the schema is as built until one exists
        assertThat(ToolPromptTexts.of(resolver, AGENT).schema("selfrender", schema)).isEqualTo(schema);
    }

    @Test
    void theVariantWhoseTemplateMatchesTheBuiltTextIsChosen() {
        ToolPromptTexts texts = ToolPromptTexts.of(resolver, AGENT);
        assertThat(texts.idFor("shell", "Runs. env-note Done.")).isEqualTo("shell");
        assertThat(texts.idFor("shell", "Runs quietly. env-note Done.")).isEqualTo("shell.quiet");
        assertThat(texts.idFor("shell", "Unrelated")).isEqualTo("shell");
        assertThat(texts.idFor("nothing_registered", "x")).isNull();
    }

    @Test
    void aVariantWithoutParametersOfItsOwnUsesTheParametersOfThePlainTool() {
        override(PromptScope.TENANT, "tool.param.shell.cmd", "Which command.");
        ToolPromptTexts texts = ToolPromptTexts.of(resolver, AGENT);
        String schema = "{\"type\":\"object\",\"properties\":{\"cmd\":{\"type\":\"string\",\"description\":\"The command.\"}}}";

        assertThat(ToolPromptSchemas.byPath(texts.schema("shell.quiet", schema))).containsEntry("cmd", "Which command.");
        assertThat(ToolPromptSchemas.byPath(texts.schema("shell", schema))).containsEntry("cmd", "Which command.");
    }

    @Test
    void currentIsTheShippedTextsOutsideAScopeAndTheBoundOneInsideIt() {
        override(PromptScope.TENANT, "tool.template.selfrender", "Overridden {roster}.");
        Map<String, String> vars = Map.of("roster", "x");

        assertThat(ToolPromptTexts.current().resolver()).isSameAs(com.agenticai.core.prompts.PromptResolver.defaultsOnly());
        assertThat(ToolPromptTexts.current().agentId()).isNull();
        String inside = ToolPromptTexts.within(ToolPromptTexts.of(resolver, AGENT), () -> ToolPromptTexts.current().description("selfrender", vars));
        assertThat(inside).isEqualTo("Overridden x.");
        assertThat(ToolPromptTexts.current().resolver()).isSameAs(com.agenticai.core.prompts.PromptResolver.defaultsOnly());
    }

    @Test
    void scopesNestAndRestore() {
        ToolPromptTexts outer = ToolPromptTexts.of(resolver, AGENT);
        ToolPromptTexts inner = ToolPromptTexts.of(resolver, UUID.randomUUID());
        ToolPromptTexts.within(
                outer,
                () -> {
                    ToolPromptTexts.within(inner, () -> assertThat(ToolPromptTexts.current().agentId()).isEqualTo(inner.agentId()));
                    assertThat(ToolPromptTexts.current().agentId()).isEqualTo(AGENT);
                    return null;
                });
    }

    @Test
    void anObjectCreatedForATenantAnswersForItOnAnotherThread() throws Exception {
        override(PromptScope.TENANT, "tool.description.plain", "Tenant A wording.");
        ToolPromptTexts texts = ToolPromptTexts.of(resolver, AGENT);
        AtomicReference<String> seen = new AtomicReference<>();
        AtomicReference<Boolean> contextLeft = new AtomicReference<>();

        Thread t =
                new Thread(
                        () -> {
                            // no tenant on this thread, as on a tool-call worker
                            seen.set(texts.overriddenDescription("plain", "Plain text."));
                            contextLeft.set(TenantContext.current().isPresent());
                        });
        t.start();
        t.join();

        assertThat(seen.get()).isEqualTo("Tenant A wording.");
        assertThat(contextLeft.get()).as("the worker thread is left without a tenant").isFalse();
    }
}
