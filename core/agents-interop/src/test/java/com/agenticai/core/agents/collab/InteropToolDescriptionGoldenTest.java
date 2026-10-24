package com.agenticai.core.agents.collab;

import static org.assertj.core.api.Assertions.assertThat;

import com.agenticai.core.prompts.PromptCatalog;
import com.agenticai.core.prompts.PromptResolver;
import com.agenticai.core.prompts.PromptRiskClass;
import com.agenticai.core.prompts.tool.ToolPromptGolden;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Golden test for the tools of this module whose description and parameter texts live in the prompt
 * registry (docs/prompts.md, "Tool descriptions"): for every tool, the text the code builds today
 * equals the shipped default of its keys, byte for byte. Adding a key without the class, or editing
 * the code's wording without the resource file, fails here.
 */
class InteropToolDescriptionGoldenTest {

    /** A tool class, the registry id it is registered under, and fields its definition reads. */
    private record Tool(String className, String id, Map<String, Object> fields) {
        static Tool of(String className) {
            return new Tool(className, null, Map.of());
        }
    }


    private static List<Tool> tools() {
        return List.of(
                Tool.of("com.agenticai.core.agents.a2a.A2aRemoteAgentToolCallback"),
                new Tool("com.agenticai.core.agents.collab.RunSubtasksToolCallback", null, Map.of("limits", new com.agenticai.core.agents.collab.CollaborationProperties())));
    }

    @Test
    void theCatalogOfTheClasspathIsSound() {
        assertThat(PromptResolver.defaultsOnly().catalog().problems()).isEmpty();
    }

    @Test
    void everyToolsRegistryDefaultsEqualWhatItsCodeBuilds() throws Exception {
        PromptCatalog catalog = PromptResolver.defaultsOnly().catalog();
        List<String> problems = new ArrayList<>();
        for (Tool tool : tools()) {
            ToolPromptGolden.Probed probed = ToolPromptGolden.probe(Class.forName(tool.className()), tool.fields());
            String id = tool.id() != null ? tool.id() : probed.name();
            problems.addAll(ToolPromptGolden.problems(catalog, id, probed.description(), probed.inputSchema()));
        }
        assertThat(problems).isEmpty();
    }

    @Test
    void everyToolKeyIsAnOrdinaryToolDescription() throws Exception {
        PromptCatalog catalog = PromptResolver.defaultsOnly().catalog();
        for (Tool tool : tools()) {
            ToolPromptGolden.Probed probed = ToolPromptGolden.probe(Class.forName(tool.className()), tool.fields());
            String id = tool.id() != null ? tool.id() : probed.name();
            var def = catalog.find("tool.description." + id).orElseThrow();
            assertThat(def.riskClass()).as(def.key()).isEqualTo(PromptRiskClass.TOOL_DESCRIPTION);
            assertThat(def.disableable()).as(def.key() + " disableable").isFalse();
            assertThat(def.title()).isNotBlank();
            assertThat(def.description()).isNotBlank();
        }
    }
}
