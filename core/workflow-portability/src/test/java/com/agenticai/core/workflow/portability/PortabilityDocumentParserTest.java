package com.agenticai.core.workflow.portability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Optional;
import org.junit.jupiter.api.Test;

class PortabilityDocumentParserTest {

    private final PortabilityDocumentParser parser = new PortabilityDocumentParser(2 * 1024 * 1024);

    @Test
    void readsJsonAndYamlAndTellsThemApart() {
        PortabilityDocumentParser.Parsed json =
                parser.parse("{\"nodes\": [], \"connections\": {}}");
        assertThat(json.fromYaml()).isFalse();
        assertThat(PortabilityFormat.detect(json.root())).contains(PortabilityFormat.N8N);

        PortabilityDocumentParser.Parsed yaml =
                parser.parse(
                        """
            kind: app
            app:
              mode: workflow
            workflow:
              graph:
                nodes: []
            """);
        assertThat(yaml.fromYaml()).isTrue();
        assertThat(PortabilityFormat.detect(yaml.root())).contains(PortabilityFormat.DIFY);
    }

    @Test
    void toleratesABomAndSurroundingWhitespace() {
        PortabilityDocumentParser.Parsed parsed =
                parser.parse("\uFEFF  \n{\"schemaVersion\": 1, \"nodes\": []}\n");
        assertThat(PortabilityFormat.detect(parsed.root())).contains(PortabilityFormat.VEGADUTA);
    }

    @Test
    void takesTheFirstWorkflowOfAnArrayAndCountsTheRest() {
        PortabilityDocumentParser.Parsed parsed =
                parser.parse("[{\"name\":\"a\"},{\"name\":\"b\"},{\"name\":\"c\"}]");
        assertThat(parsed.root().get("name").asString()).isEqualTo("a");
        assertThat(parsed.dropped()).isEqualTo(2);
    }

    @Test
    void rejectsEmptyScalarAndEmptyListInput() {
        assertThatThrownBy(() -> parser.parse("   ")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> parser.parse("just some words"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> parser.parse("[]")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> parser.parse("[1, 2]"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsJsonNestedPastTheLimit() {
        String deep = "[".repeat(200) + "]".repeat(200);
        assertThatThrownBy(() -> parser.parse(deep))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("nested");
    }

    @Test
    void detectionRefusesToGuess() {
        assertThat(
                        PortabilityFormat.detect(
                                com.agenticai.core.json.JsonMappers.plain().createObjectNode()))
                .isEqualTo(Optional.empty());
        assertThat(PortabilityFormat.parse("N8N")).contains(PortabilityFormat.N8N);
        assertThat(PortabilityFormat.parse("zapier")).isEmpty();
        assertThat(PortabilityFormat.isAuto(" AUTO ")).isTrue();
    }

    @Test
    void aDifyChatAppWithoutAGraphIsNotDetectedAsAWorkflow() {
        PortabilityDocumentParser.Parsed chat =
                parser.parse(
                        """
            kind: app
            app:
              mode: chat
            model_config:
              model: {}
            """);
        // `kind: app` + `app` is still Dify; the mapper then explains why a chat app has no
        // workflow to import.
        assertThat(PortabilityFormat.detect(chat.root())).contains(PortabilityFormat.DIFY);
        assertThatThrownBy(
                        () ->
                                DifyImportMapper.map(
                                        com.agenticai.core.json.JsonMappers.plain(), chat.root()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("chat");
    }
}
