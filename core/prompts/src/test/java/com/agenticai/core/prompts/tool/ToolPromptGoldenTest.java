package com.agenticai.core.prompts.tool;

import static org.assertj.core.api.Assertions.assertThat;

import com.agenticai.core.prompts.PromptCatalog;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** The golden helper itself: it must fail when the registry and the code disagree. */
class ToolPromptGoldenTest {

    /** A tool as the code builds it. */
    public static final class SampleTool {
        private String suffix;

        public Object getToolDefinition() {
            return new Definition(
                    "sample_tool",
                    "Does a thing." + (suffix == null ? "" : suffix),
                    "{\"type\":\"object\",\"properties\":{\"maxChars\":{\"type\":\"integer\",\"description\":\"Cap.\"}}}");
        }
    }

    public record Definition(String name, String description, String inputSchema) {}

    private static PromptCatalog catalog(String description, String param) {
        return PromptCatalog.fromSources(
                Map.of(
                        "prompts/en/tool.description.sample_tool.md",
                        "---\ntitle: Sample\nrisk: TOOL_DESCRIPTION\nstrictPlaceholders: false\n---\n" + description + "\n",
                        "prompts/en/tool.param.sample_tool.max_chars.md",
                        "---\ntitle: Sample cap\nrisk: TOOL_DESCRIPTION\nstrictPlaceholders: false\n---\n" + param + "\n"),
                "en",
                4000);
    }

    @Test
    void probeReadsTheDefinitionWithoutRunningAConstructorAndSetsFields() {
        assertThat(ToolPromptGolden.probe(SampleTool.class).description()).isEqualTo("Does a thing.");
        assertThat(ToolPromptGolden.probe(SampleTool.class, Map.of("suffix", " More.")).description())
                .isEqualTo("Does a thing. More.");
    }

    @Test
    void matchingRegistryHasNoProblems() {
        ToolPromptGolden.Probed p = ToolPromptGolden.probe(SampleTool.class);
        assertThat(ToolPromptGolden.problems(catalog("Does a thing.", "Cap."), p.name(), p.description(), p.inputSchema()))
                .isEmpty();
    }

    @Test
    void aDriftedDescriptionOrParameterIsReported() {
        ToolPromptGolden.Probed p = ToolPromptGolden.probe(SampleTool.class);
        assertThat(ToolPromptGolden.problems(catalog("Does another thing.", "Cap."), p.name(), p.description(), p.inputSchema()))
                .singleElement()
                .asString()
                .contains("tool.description.sample_tool", "differs");
        assertThat(ToolPromptGolden.problems(catalog("Does a thing.", "Cap!"), p.name(), p.description(), p.inputSchema()))
                .singleElement()
                .asString()
                .contains("tool.param.sample_tool.max_chars", "differs");
    }

    @Test
    void aMissingKeyAndAnOrphanKeyAreReported() {
        ToolPromptGolden.Probed p = ToolPromptGolden.probe(SampleTool.class);
        PromptCatalog onlyDescription =
                PromptCatalog.fromSources(
                        Map.of(
                                "prompts/en/tool.description.sample_tool.md",
                                "---\ntitle: Sample\nrisk: TOOL_DESCRIPTION\n---\nDoes a thing.\n"),
                        "en",
                        4000);
        assertThat(ToolPromptGolden.problems(onlyDescription, p.name(), p.description(), p.inputSchema()))
                .singleElement()
                .asString()
                .contains("tool.param.sample_tool.max_chars", "not registered");

        PromptCatalog orphan =
                PromptCatalog.fromSources(
                        Map.of(
                                "prompts/en/tool.description.sample_tool.md",
                                "---\ntitle: Sample\nrisk: TOOL_DESCRIPTION\n---\nDoes a thing.\n",
                                "prompts/en/tool.param.sample_tool.max_chars.md",
                                "---\ntitle: a\nrisk: TOOL_DESCRIPTION\n---\nCap.\n",
                                "prompts/en/tool.param.sample_tool.gone.md",
                                "---\ntitle: b\nrisk: TOOL_DESCRIPTION\n---\nGone.\n"),
                        "en",
                        4000);
        assertThat(ToolPromptGolden.problems(orphan, p.name(), p.description(), p.inputSchema()))
                .singleElement()
                .asString()
                .contains("tool.param.sample_tool.gone", "no such parameter");
    }
}
