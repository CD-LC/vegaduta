package com.agenticai.core.agents.tools;

import static org.assertj.core.api.Assertions.assertThat;

import com.agenticai.core.agents.memory.RememberFactToolCallback;
import com.agenticai.core.agents.skills.tools.LoadSkillToolCallback;
import com.agenticai.core.agents.tools.context.RestoreToolResultToolCallback;
import com.agenticai.core.prompts.DefaultPromptResolver;
import com.agenticai.core.prompts.InMemoryPromptOverrideStore;
import com.agenticai.core.prompts.PromptCatalog;
import com.agenticai.core.prompts.PromptOverride;
import com.agenticai.core.prompts.PromptProperties;
import com.agenticai.core.prompts.PromptResolver;
import com.agenticai.core.prompts.PromptRiskClass;
import com.agenticai.core.prompts.PromptScope;
import com.agenticai.core.prompts.tool.ToolPromptGolden;
import com.agenticai.core.prompts.tool.ToolPromptKeys;
import com.agenticai.core.prompts.tool.ToolPromptSchemas;
import com.agenticai.core.security.TenantContext;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;

/**
 * Golden test (package 4e-2) for the memory, skill and tool-context tools of core/agents-turn whose
 * description and parameter texts live in the prompt registry: for every tool, the text its code
 * builds equals the shipped default of its keys, byte for byte. Plus the real path: a registry
 * override reaches the tool the way a turn's tool list gets it (through {@link
 * ToolDescriptionResolver}), per agent, without touching name or schema shape.
 */
class MemorySkillToolDescriptionGoldenTest {

    record Tool(String id, String type) {}

    static final List<Tool> TOOLS =
            List.of(
            new Tool("remember_fact", "com.agenticai.core.agents.memory.RememberFactToolCallback"),
            new Tool("remember_shared_fact", "com.agenticai.core.agents.memory.RememberSharedFactToolCallback"),
            new Tool("improve_skill", "com.agenticai.core.agents.skills.tools.ImproveSkillToolCallback"),
            new Tool("load_skill", "com.agenticai.core.agents.skills.tools.LoadSkillToolCallback"),
            new Tool("propose_skill", "com.agenticai.core.agents.skills.tools.ProposeSkillToolCallback"),
            new Tool("read_skill_file", "com.agenticai.core.agents.skills.tools.ReadSkillFileToolCallback"),
            new Tool("run_skill_script", "com.agenticai.core.agents.skills.tools.RunSkillScriptToolCallback"),
            new Tool("restore_tool_result", "com.agenticai.core.agents.tools.context.RestoreToolResultToolCallback"));

    private static final UUID AGENT_A = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
    private static final UUID AGENT_B = UUID.fromString("00000000-0000-0000-0000-0000000000b1");

    private final PromptCatalog catalog = PromptResolver.defaultsOnly().catalog();
    private final InMemoryPromptOverrideStore store = new InMemoryPromptOverrideStore();
    private final DefaultPromptResolver resolver = new DefaultPromptResolver(catalog, store, new PromptProperties());
    private final ToolDescriptionResolver decorator = ToolDescriptionResolver.of(resolver);

    @BeforeEach
    void tenant() {
        TenantContext.set(TenantContext.of("tenant-a", "admin@a", Set.of("ROLE_tenant-admin")));
    }

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    @Test
    void theCatalogOfTheClasspathIsSound() {
        assertThat(catalog.problems()).isEmpty();
    }

    @Test
    void everyToolsRegistryDefaultsEqualWhatItsCodeBuilds() throws Exception {
        List<String> problems = new ArrayList<>();
        for (Tool tool : TOOLS) {
            ToolPromptGolden.Probed probed = ToolPromptGolden.probe(Class.forName(tool.type()), Map.of());
            assertThat(probed.name()).as(tool.type()).isEqualTo(tool.id());
            problems.addAll(ToolPromptGolden.problems(catalog, tool.id(), probed.description(), probed.inputSchema()));
        }
        assertThat(problems).isEmpty();
    }

    @Test
    void everyToolKeyIsADeclaredToolDescriptionWithoutPlaceholders() {
        for (Tool tool : TOOLS) {
            var def = catalog.find("tool.description." + tool.id()).orElseThrow();
            assertThat(def.riskClass()).as(def.key()).isEqualTo(PromptRiskClass.TOOL_DESCRIPTION);
            assertThat(def.disableable()).as(def.key() + " disableable").isFalse();
            assertThat(def.placeholders()).as(def.key() + " placeholders").isEmpty();
            // the only one that runs code: a weaker description could talk the model past the sandbox notice
            assertThat(def.securityCritical()).as(def.key() + " securityCritical").isEqualTo(tool.id().equals("run_skill_script"));
        }
    }

    @Test
    void withoutAnOverrideTheToolListIsUnchanged() {
        ToolCallback remember = new RememberFactToolCallback(null, AGENT_A, "subject");
        ToolCallback load = new LoadSkillToolCallback(null, AGENT_A, false);
        ToolCallback restore = new RestoreToolResultToolCallback(null, AGENT_A, "session-1");
        List<ToolCallback> in = List.of(remember, load, restore);

        assertThat(decorator.apply(in, AGENT_A)).containsExactlyElementsOf(in);
    }

    @Test
    void anAgentScopeOverrideChangesThatAgentsToolOnly() {
        ToolCallback remember = new RememberFactToolCallback(null, AGENT_A, "subject");
        String before = remember.getToolDefinition().inputSchema();
        put(PromptScope.AGENT, AGENT_A.toString(), "tool.description.remember_fact", "Save a durable user fact.");
        put(PromptScope.AGENT, AGENT_A.toString(), ToolPromptKeys.param("remember_fact", "fact"), "The fact, one sentence.");

        ToolCallback forA = decorator.apply(remember, AGENT_A);
        assertThat(forA.getToolDefinition().name()).isEqualTo("remember_fact");
        assertThat(forA.getToolDefinition().description()).isEqualTo("Save a durable user fact.");
        assertThat(ToolPromptSchemas.byPath(forA.getToolDefinition().inputSchema()))
                .containsEntry("fact", "The fact, one sentence.");
        assertThat(ToolPromptSchemas.sameShape(before, forA.getToolDefinition().inputSchema())).isTrue();

        assertThat(decorator.apply(remember, AGENT_B)).isSameAs(remember);
        assertThat(decorator.apply(remember, null)).isSameAs(remember);
    }

    @Test
    void aTenantOverrideReachesEveryAgentAndAnAgentOverrideWinsOverIt() {
        ToolCallback load = new LoadSkillToolCallback(null, AGENT_A, false);
        put(PromptScope.TENANT, "", "tool.description.load_skill", "Tenant wording.");
        put(PromptScope.AGENT, AGENT_B.toString(), "tool.description.load_skill", "Agent B wording.");

        assertThat(decorator.apply(load, AGENT_A).getToolDefinition().description()).isEqualTo("Tenant wording.");
        assertThat(decorator.apply(load, AGENT_B).getToolDefinition().description()).isEqualTo("Agent B wording.");
    }

    @Test
    void anOverrideNeverChangesTheNameOrTheShape() {
        ToolCallback restore = new RestoreToolResultToolCallback(null, AGENT_A, "session-1");
        put(PromptScope.PLATFORM, "", "tool.description.restore_tool_result", "Platform wording.");
        put(PromptScope.PLATFORM, "", ToolPromptKeys.param("restore_tool_result", "stepId"), "The step.");

        var def = decorator.apply(restore, AGENT_A).getToolDefinition();
        assertThat(def.name()).isEqualTo(RestoreToolResultToolCallback.NAME);
        assertThat(def.description()).isEqualTo("Platform wording.");
        assertThat(ToolPromptSchemas.sameShape(restore.getToolDefinition().inputSchema(), def.inputSchema())).isTrue();
        assertThat(ToolPromptSchemas.byPath(def.inputSchema())).containsEntry("stepId", "The step.");
    }

    private void put(PromptScope scope, String scopeId, String key, String text) {
        store.put(new PromptOverride(scope, scopeId, key, "en", text, true, 1, "tester", Instant.now()));
        resolver.evictAll();
    }
}
