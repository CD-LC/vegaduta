package com.agenticai.core.agents.tools;

import static org.assertj.core.api.Assertions.assertThat;

import com.agenticai.core.prompts.PromptCatalog;
import com.agenticai.core.prompts.PromptResolver;
import com.agenticai.core.prompts.tool.ToolPromptGolden;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Golden test for the built-in tools of core/agents-tools whose description and parameter texts
 * live in the prompt registry (docs/prompts.md, "Tool descriptions"): for every tool, the text the
 * code builds today equals the shipped default of its keys, byte for byte. A tool class not in
 * this list has no keys; adding a key without the class, or editing the code's wording without the
 * resource file, fails here.
 */
class BuiltInToolDescriptionGoldenTest {

    private static final String P = "com.agenticai.core.agents.tools.";

    /** Tool class name (relative to the tools package) to the registry id it is registered under. */
    private static final List<String> TOOLS =
            List.of(
                    "apiguide.ApiGuideToolCallback",
                    "audio.SpeakToolCallback",
                    "commerce.CheckPaymentStatusToolCallback",
                    "commerce.CreatePaymentLinkToolCallback",
                    "device.ReadDeviceAppUsageToolCallback",
                    "device.ReadDeviceNotificationsToolCallback",
                    "device.ReadDeviceScreenToolCallback",
                    "device.ReadDeviceSmsToolCallback",
                    "documentgen.GenerateDocumentToolCallback",
                    "e2b.E2bRunCodeToolCallback",
                    "messaging.ChannelMessagesToolCallback",
                    "messaging.ChannelSendMessageToolCallback",
                    "messaging.ChannelSessionActionToolCallback",
                    "messaging.ChannelSessionsToolCallback",
                    "messaging.ChannelsOverviewToolCallback",
                    "messaging.ForwardAttachmentToolCallback",
                    "messaging.RequestHumanToolCallback",
                    "messaging.SearchConversationHistoryToolCallback",
                    "messaging.TelegramMessagesToolCallback",
                    "platformguide.SetupGuideToolCallback",
                    "search.CallToolToolCallback",
                    "search.SearchToolsToolCallback",
                    "tables.DataTableQueryToolCallback",
                    "tables.DataTableWriteToolCallback");

    @Test
    void theCatalogOfTheClasspathIsSound() {
        assertThat(PromptResolver.defaultsOnly().catalog().problems()).isEmpty();
    }

    @Test
    void everyToolsRegistryDefaultsEqualWhatItsCodeBuilds() throws Exception {
        PromptCatalog catalog = PromptResolver.defaultsOnly().catalog();
        List<String> problems = new ArrayList<>();
        for (String tool : TOOLS) {
            ToolPromptGolden.Probed probed = ToolPromptGolden.probe(Class.forName(P + tool), Map.of());
            problems.addAll(ToolPromptGolden.problems(catalog, probed.name(), probed.description(), probed.inputSchema()));
        }
        assertThat(problems).isEmpty();
    }

    @Test
    void everyToolKeyIsADeclaredToolDescription() throws Exception {
        PromptCatalog catalog = PromptResolver.defaultsOnly().catalog();
        for (String tool : TOOLS) {
            ToolPromptGolden.Probed probed = ToolPromptGolden.probe(Class.forName(P + tool), Map.of());
            var def = catalog.find("tool.description." + probed.name()).orElseThrow();
            assertThat(def.riskClass()).as(def.key()).isEqualTo(com.agenticai.core.prompts.PromptRiskClass.TOOL_DESCRIPTION);
            assertThat(def.disableable()).as(def.key() + " disableable").isFalse();
            assertThat(def.placeholders()).as(def.key() + " placeholders").isEmpty();
            assertThat(def.title()).isNotBlank();
            assertThat(def.description()).isNotBlank();
        }
    }
}
