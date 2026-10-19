package com.agenticai.core.agents.tools.browseruse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.agenticai.core.agents.tools.classification.BuiltInToolClassifications;
import com.agenticai.core.agents.tools.classification.ToolClassification;
import com.agenticai.core.agents.tools.classification.ToolSensitivity;
import com.agenticai.core.agents.tools.classification.ToolTrustTier;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;

/**
 * The eight v2 tool callbacks: unique names, valid input schemas, no-tenant-context failing as a
 * clean JSON error, argument helpers, and that every one of them is classified in {@link
 * BuiltInToolClassifications} the way the class javadocs promise (unclassified would mean Blocked).
 */
class BrowserV2ToolDefinitionsTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static List<ToolCallback> allV2Tools() {
        BrowserUseSessionManager manager = mock(BrowserUseSessionManager.class);
        UUID agent = UUID.randomUUID();
        return List.of(
                new BrowserSnapshotToolCallback(manager, agent, "s"),
                new BrowserClickElementToolCallback(manager, agent, "s"),
                new BrowserConfirmClickToolCallback(manager, agent, "s"),
                new BrowserTypeIntoToolCallback(manager, agent, "s"),
                new BrowserSelectOptionToolCallback(manager, agent, "s"),
                new BrowserScrollToolCallback(manager, agent, "s"),
                new BrowserGoBackToolCallback(manager, agent, "s"),
                new BrowserExtractTextToolCallback(manager, agent, "s"));
    }

    @Test
    void namesAreUniqueAndDoNotCollideWithTheLegacyFive() {
        Set<String> names = new HashSet<>();
        for (ToolCallback tool : allV2Tools()) {
            assertThat(names.add(tool.getToolDefinition().name()))
                    .as(tool.getToolDefinition().name())
                    .isTrue();
        }
        assertThat(names)
                .containsExactlyInAnyOrder(
                        "browser_snapshot",
                        "browser_click_element",
                        "browser_confirm_click",
                        "browser_type_into",
                        "browser_select_option",
                        "browser_scroll",
                        "browser_go_back",
                        "browser_extract_text");
        assertThat(names)
                .doesNotContain(
                        "browser_navigate",
                        "browser_click",
                        "browser_type",
                        "browser_read_page_text",
                        "browser_screenshot");
    }

    @Test
    void everyInputSchemaIsValidJsonObjectSchemaAndDescriptionsStayShort() throws Exception {
        for (ToolCallback tool : allV2Tools()) {
            String name = tool.getToolDefinition().name();
            JsonNode schema = MAPPER.readTree(tool.getToolDefinition().inputSchema());
            assertThat(schema.path("type").asText()).as(name).isEqualTo("object");
            assertThat(schema.has("properties")).as(name).isTrue();
            // tool schemas are token-budgeted (conventions #9): keep each description to a few
            // sentences
            assertThat(tool.getToolDefinition().description().length()).as(name).isLessThan(480);
            for (JsonNode required : schema.path("required")) {
                assertThat(schema.path("properties").has(required.asText()))
                        .as(name + " required " + required)
                        .isTrue();
            }
        }
    }

    @Test
    void indexIsRequiredWhereAnActionNeedsOne() throws Exception {
        for (ToolCallback tool : allV2Tools()) {
            String name = tool.getToolDefinition().name();
            JsonNode required =
                    MAPPER.readTree(tool.getToolDefinition().inputSchema()).path("required");
            boolean needsIndex =
                    Set.of(
                                    "browser_click_element",
                                    "browser_confirm_click",
                                    "browser_type_into",
                                    "browser_select_option")
                            .contains(name);
            assertThat(required.toString().contains("\"index\"")).as(name).isEqualTo(needsIndex);
        }
    }

    @Test
    void withoutATenantContextEveryToolReturnsACleanJsonError() throws Exception {
        for (ToolCallback tool : allV2Tools()) {
            String result =
                    tool.call(
                            "{\"index\": 1, \"text\": \"x\", \"label\": \"y\", \"value\": \"v\"}");
            JsonNode json = MAPPER.readTree(result);
            assertThat(json.path("error").asText())
                    .as(tool.getToolDefinition().name())
                    .contains("tenant");
        }
    }

    @Test
    void indexArgumentParsingIsTolerantButStrict() {
        assertThat(BrowserV2ToolBase.asIndex(3)).isEqualTo(3);
        assertThat(BrowserV2ToolBase.asIndex(3.0)).isEqualTo(3);
        assertThat(BrowserV2ToolBase.asIndex(" 12 ")).isEqualTo(12);
        assertThat(BrowserV2ToolBase.asIndex(0)).isNull();
        assertThat(BrowserV2ToolBase.asIndex(-4)).isNull();
        assertThat(BrowserV2ToolBase.asIndex(2.5)).isNull();
        assertThat(BrowserV2ToolBase.asIndex("abc")).isNull();
        assertThat(BrowserV2ToolBase.asIndex(null)).isNull();
        assertThat(BrowserV2ToolBase.asIndex(1_000_000)).isNull();
    }

    @Test
    void extractTextClampsItsCharCap() {
        assertThat(BrowserExtractTextToolCallback.clampMaxChars(null)).isEqualTo(4_000);
        assertThat(BrowserExtractTextToolCallback.clampMaxChars("0")).isEqualTo(4_000);
        assertThat(BrowserExtractTextToolCallback.clampMaxChars("junk")).isEqualTo(4_000);
        assertThat(BrowserExtractTextToolCallback.clampMaxChars(500)).isEqualTo(500);
        assertThat(BrowserExtractTextToolCallback.clampMaxChars(10_000_000)).isEqualTo(20_000);
    }

    // ---- classification
    // ---------------------------------------------------------------------------------------

    private static ToolClassification classification(String name) {
        ToolClassification c = BuiltInToolClassifications.all().get(name);
        assertThat(c).as("classification of " + name).isNotNull();
        return c;
    }

    @Test
    void everyV2ToolIsClassifiedAndOnlyConfirmClickRequiresHumanConfirmation() {
        for (ToolCallback tool : allV2Tools()) {
            String name = tool.getToolDefinition().name();
            ToolClassification c = classification(name);
            assertThat(c.trust()).as(name).isEqualTo(ToolTrustTier.UNTRUSTED_EXTERNAL);
            assertThat(c.needsToolContext()).as(name).isTrue();
            assertThat(c.requiresHumanConfirmation())
                    .as(name)
                    .isEqualTo("browser_confirm_click".equals(name));
        }
    }

    @Test
    void readToolsAreReadPublicAndWriteToolsAreEgressDurable() {
        for (String read :
                List.of(
                        "browser_snapshot",
                        "browser_extract_text",
                        "browser_scroll",
                        "browser_go_back")) {
            assertThat(classification(read).sensitivity())
                    .as(read)
                    .isEqualTo(ToolSensitivity.READ_PUBLIC);
            assertThat(classification(read).irreversible()).as(read).isFalse();
        }
        for (String write :
                List.of(
                        "browser_click_element",
                        "browser_type_into",
                        "browser_select_option",
                        "browser_confirm_click")) {
            ToolClassification c = classification(write);
            assertThat(c.sensitivity()).as(write).isEqualTo(ToolSensitivity.EGRESS);
            assertThat(c.determinism().replayMustUseJournaledResponse()).as(write).isTrue();
        }
        assertThat(classification("browser_click_element").irreversible()).isFalse();
        assertThat(classification("browser_confirm_click").irreversible()).isTrue();
    }

    @Test
    void theLegacyToolsKeepTheirClassification() {
        assertThat(classification("browser_click").irreversible()).isTrue();
        assertThat(classification("browser_type").irreversible()).isTrue();
        assertThat(classification("browser_navigate").sensitivity())
                .isEqualTo(ToolSensitivity.READ_PUBLIC);
    }
}
