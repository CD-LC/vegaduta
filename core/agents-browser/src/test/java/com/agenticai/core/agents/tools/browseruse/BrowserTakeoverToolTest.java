package com.agenticai.core.agents.tools.browseruse;

import static com.agenticai.core.agents.tools.browseruse.BrowserTakeoverFixtures.AGENT;
import static com.agenticai.core.agents.tools.browseruse.BrowserTakeoverFixtures.CHAT_SESSION;
import static com.agenticai.core.agents.tools.browseruse.BrowserTakeoverFixtures.TENANT;
import static com.agenticai.core.agents.tools.browseruse.BrowserTakeoverFixtures.USER;
import static com.agenticai.core.agents.tools.browseruse.BrowserTakeoverFixtures.provider;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.agenticai.core.agents.tools.browseruse.BrowserTakeoverFixtures.Rig;
import com.agenticai.core.agents.tools.classification.BuiltInToolClassifications;
import com.agenticai.core.agents.tools.classification.ToolClassification;
import com.agenticai.core.agents.tools.classification.ToolSensitivity;
import com.agenticai.core.agents.tools.classification.ToolTrustTier;
import com.agenticai.core.security.TenantContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;

/**
 * {@code browser_request_user}: offered only with takeover on, parks the agent, reports status
 * once, classified non-destructive.
 */
class BrowserTakeoverToolTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    private static BrowserRequestUserToolCallback tool(Rig rig) {
        return new BrowserRequestUserToolCallback(
                mock(BrowserUseSessionManager.class), rig.service, AGENT, CHAT_SESSION);
    }

    private static JsonNode call(BrowserRequestUserToolCallback tool, String input)
            throws Exception {
        return JSON.readTree(tool.call(input));
    }

    @Test
    void theToolDefinitionIsAValidShortSchema() throws Exception {
        BrowserRequestUserToolCallback tool = tool(new Rig(BrowserTakeoverFixtures.on()));
        assertThat(tool.getToolDefinition().name()).isEqualTo("browser_request_user");
        assertThat(JSON.readTree(tool.getToolDefinition().inputSchema()).path("type").asText())
                .isEqualTo("object");
        assertThat(tool.getToolDefinition().description().length()).isLessThan(700);
    }

    @Test
    void requestingParksTheAgentAndStatusReportsTheOutcomeOnce() throws Exception {
        Rig rig = new Rig(BrowserTakeoverFixtures.on());
        BrowserRequestUserToolCallback tool = tool(rig);
        TenantContext.set(TenantContext.of(TENANT, USER, Set.of()));

        JsonNode parked = call(tool, "{\"reason\":\"Solve the captcha\",\"kind\":\"captcha\"}");
        assertThat(parked.path("state").asText()).isEqualTo("PAUSED_FOR_USER");
        assertThat(parked.path("message").asText()).contains("Do NOT call any other browser tool");
        assertThat(call(tool, "{\"action\":\"status\"}").path("state").asText())
                .isEqualTo("PAUSED_FOR_USER");

        UUID id =
                UUID.fromString(rig.service.status(TENANT, USER, AGENT, CHAT_SESSION).takeoverId());
        rig.service.begin(TENANT, USER, id);
        assertThat(call(tool, "{\"action\":\"status\"}").path("state").asText())
                .isEqualTo("USER_CONTROL");
        rig.service.handBack(TENANT, USER, id);

        JsonNode done = call(tool, "{\"action\":\"status\"}");
        assertThat(done.path("state").asText()).isEqualTo("AGENT_CONTROL");
        assertThat(done.path("message").asText()).contains("The user finished the requested step");
        assertThat(call(tool, "{\"action\":\"status\"}").path("message").asText())
                .doesNotContain("The user finished");
    }

    @Test
    void badArgumentsAndMissingContextComeBackAsErrorsNotExceptions() throws Exception {
        Rig rig = new Rig(BrowserTakeoverFixtures.on());
        BrowserRequestUserToolCallback tool = tool(rig);
        assertThat(call(tool, "{\"reason\":\"x\"}").path("error").asText())
                .as("no tenant context")
                .contains("tenant");
        TenantContext.set(TenantContext.of(TENANT, USER, Set.of()));
        assertThat(call(tool, "{}").path("error").asText()).contains("reason");
        assertThat(call(tool, "{\"action\":\"teleport\"}").path("error").asText())
                .contains("action");
        assertThat(rig.service.controlFor(rig.key).state())
                .isEqualTo(BrowserTakeoverState.AGENT_CONTROL);
    }

    @Test
    void withNoBrowserSessionTheErrorSaysToNavigateFirst() throws Exception {
        Rig rig = new Rig(BrowserTakeoverFixtures.on());
        BrowserRequestUserToolCallback tool =
                new BrowserRequestUserToolCallback(
                        mock(BrowserUseSessionManager.class),
                        rig.service,
                        AGENT,
                        "a-chat-with-no-browser");
        TenantContext.set(TenantContext.of(TENANT, USER, Set.of()));
        assertThat(call(tool, "{\"reason\":\"x\"}").path("error").asText())
                .contains("browser_navigate");
    }

    @Test
    void theToolIsOfferedOnlyWhenTakeoverAndBrowserV2AreBothOn() {
        BrowserUseSessionManager v2Manager = mock(BrowserUseSessionManager.class);
        when(v2Manager.isV2Enabled()).thenReturn(true);
        BrowserUseSessionManager v1Manager = mock(BrowserUseSessionManager.class);

        BrowserAutomationTools plain =
                new BrowserAutomationTools(provider(null), provider(null), false, "");
        assertThat(names(plain, v2Manager))
                .as("flag off / no service: tool list unchanged")
                .isEmpty();

        BrowserAutomationTools tools =
                new BrowserAutomationTools(provider(null), provider(null), false, "");
        tools.setTakeover(new Rig(BrowserTakeoverFixtures.on()).service);
        assertThat(names(tools, v2Manager)).containsExactly("browser_request_user");
        assertThat(names(tools, v1Manager)).as("needs browser v2").isEmpty();

        BrowserAutomationTools off =
                new BrowserAutomationTools(provider(null), provider(null), false, "");
        off.setTakeover(
                new Rig(BrowserTakeoverFixtures.settings(false, true, "return", false)).service);
        assertThat(names(off, v2Manager)).as("takeover flag off").isEmpty();
    }

    private static List<String> names(
            BrowserAutomationTools tools, BrowserUseSessionManager manager) {
        return tools.create(manager, AGENT, CHAT_SESSION).stream()
                .map(ToolCallback::getToolDefinition)
                .map(d -> d.name())
                .toList();
    }

    @Test
    void theToolIsClassifiedSoItIsNotBlockedAndIsNotIrreversible() {
        ToolClassification classification =
                BuiltInToolClassifications.all().get(BrowserRequestUserToolCallback.NAME);
        assertThat(classification).isNotNull();
        assertThat(classification.trust()).isEqualTo(ToolTrustTier.UNTRUSTED_EXTERNAL);
        assertThat(classification.sensitivity()).isEqualTo(ToolSensitivity.READ_PUBLIC);
        assertThat(classification.irreversible())
                .as("pausing the agent changes nothing on a site")
                .isFalse();
    }
}
