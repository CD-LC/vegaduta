package com.agenticai.core.prompts.tool;

import static org.assertj.core.api.Assertions.assertThat;

import com.agenticai.core.prompts.PromptKeys;
import org.junit.jupiter.api.Test;

class ToolPromptKeysTest {

    @Test
    void descriptionAndParamKeysFollowTheConvention() {
        assertThat(ToolPromptKeys.description("generate_document")).isEqualTo("tool.description.generate_document");
        assertThat(ToolPromptKeys.param("web_search", "query")).isEqualTo("tool.param.web_search.query");
        assertThat(ToolPromptKeys.param("run_subtasks", "tasks.brief")).isEqualTo("tool.param.run_subtasks.tasks.brief");
    }

    @Test
    void camelCaseAndOddCharactersBecomeValidSegments() {
        assertThat(ToolPromptKeys.segment("maxChars")).isEqualTo("max_chars");
        assertThat(ToolPromptKeys.segment("contextSummary")).isEqualTo("context_summary");
        assertThat(ToolPromptKeys.segment("HTTPMethod")).isEqualTo("httpmethod");
        assertThat(ToolPromptKeys.segment("exclude_image_sourced")).isEqualTo("exclude_image_sourced");
        assertThat(ToolPromptKeys.segment("a b$c")).isEqualTo("a_b_c");
        assertThat(ToolPromptKeys.segment("_x")).isEqualTo("p_x");
        assertThat(ToolPromptKeys.segment("")).isEqualTo("p");
    }

    @Test
    void everyBuiltKeyIsAValidPromptKey() {
        for (String raw : new String[] {"url", "maxChars", "2fa", "_hidden", "A.B", "x-y"}) {
            assertThat(PromptKeys.isValid(ToolPromptKeys.param("tool_x", raw))).as(raw).isTrue();
        }
    }

    @Test
    void idsAreToolNamesWithAnOptionalVariant() {
        assertThat(ToolPromptKeys.isValidId("http_request")).isTrue();
        assertThat(ToolPromptKeys.isValidId("http_request.safe")).isTrue();
        assertThat(ToolPromptKeys.isValidId("a.b.c")).isFalse();
        assertThat(ToolPromptKeys.isValidId("Upper")).isFalse();
        assertThat(ToolPromptKeys.isValidId("mcp__server__tool")).isTrue();
        assertThat(ToolPromptKeys.isValidId(null)).isFalse();
        assertThat(ToolPromptKeys.toolName("http_request.safe")).isEqualTo("http_request");
        assertThat(ToolPromptKeys.toolName("web_search")).isEqualTo("web_search");
    }
}
