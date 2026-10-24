package com.agenticai.core.prompts.tool;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ToolPromptSchemasTest {

    private static final String SCHEMA =
            """
            {"type":"object","properties":{
              "question":{"type":"string","description":"The question."},
              "maxChars":{"type":"integer","description":"Cap."},
              "tasks":{"type":"array","description":"The tasks.","items":{"type":"object","properties":{
                "brief":{"type":"string","description":"One brief."},
                "role":{"type":"string"}},"required":["brief"]},"maxItems":5},
              "opts":{"type":"object","description":"Options.","properties":{"deep":{"type":"boolean","description":"Go deep."}}}
            },"required":["question"]}
            """;

    @Test
    void listsEveryDescribedParameterByItsPathAndSkipsTheItemsLevel() {
        assertThat(ToolPromptSchemas.params(SCHEMA))
                .extracting(ToolPromptSchemas.Param::path)
                .containsExactly("question", "maxChars", "tasks", "tasks.brief", "opts", "opts.deep");
        assertThat(ToolPromptSchemas.byPath(SCHEMA)).containsEntry("tasks.brief", "One brief.");
    }

    @Test
    void replacesOnlyTheNamedDescriptionsAndKeepsTheShape() {
        String out = ToolPromptSchemas.withDescriptions(SCHEMA, Map.of("question", "Ask it.", "tasks.brief", "A brief."));

        assertThat(ToolPromptSchemas.byPath(out))
                .containsEntry("question", "Ask it.")
                .containsEntry("tasks.brief", "A brief.")
                .containsEntry("maxChars", "Cap.");
        assertThat(ToolPromptSchemas.sameShape(SCHEMA, out)).isTrue();
        assertThat(out).contains("\"maxItems\":5").contains("\"required\":[\"question\"]");
    }

    @Test
    void aPathWithoutADescriptionIsNotCreated() {
        String out = ToolPromptSchemas.withDescriptions(SCHEMA, Map.of("tasks.role", "Invented."));
        assertThat(out).isEqualTo(SCHEMA);
        assertThat(ToolPromptSchemas.byPath(out)).doesNotContainKey("tasks.role");
    }

    @Test
    void sameShapeSeesAChangedTypeOrAddedProperty() {
        String retyped = SCHEMA.replace("\"maxChars\":{\"type\":\"integer\"", "\"maxChars\":{\"type\":\"string\"");
        assertThat(ToolPromptSchemas.sameShape(SCHEMA, retyped)).isFalse();
        String extra = SCHEMA.replace("\"required\":[\"question\"]", "\"required\":[\"question\",\"maxChars\"]");
        assertThat(ToolPromptSchemas.sameShape(SCHEMA, extra)).isFalse();
    }

    @Test
    void anUnparsableOrEmptySchemaIsLeftAlone() {
        assertThat(ToolPromptSchemas.params("not json")).isEmpty();
        assertThat(ToolPromptSchemas.params(null)).isEmpty();
        assertThat(ToolPromptSchemas.withDescriptions("not json", Map.of("a", "b"))).isEqualTo("not json");
        assertThat(ToolPromptSchemas.params("{}")).isEqualTo(List.of());
    }
}
