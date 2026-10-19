package com.agenticai.core.agents.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.agenticai.core.agents.tools.classification.BuiltInToolClassifications;
import com.agenticai.core.agents.tools.classification.LethalTrifectaDetector;
import com.agenticai.core.agents.tools.classification.ToolClassification;
import com.agenticai.core.agents.tools.classification.ToolClassificationDecision;
import com.agenticai.core.agents.tools.classification.ToolClassificationRegistry;
import com.agenticai.core.agents.tools.classification.ToolClassificationRepository;
import com.agenticai.core.agents.tools.classification.ToolSensitivity;
import com.agenticai.core.agents.tools.classification.ToolTrustTier;
import com.agenticai.core.agents.tools.web.HttpRequestExecutor;
import com.agenticai.core.agents.tools.web.HttpRequestSafetyConfig;
import com.agenticai.core.agents.tools.web.HttpRequestToolCallback;
import com.agenticai.core.guardrails.ToolDispatchGate;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.ObjectProvider;

/**
 * Which {@code http_request} callbacks an agent is offered ({@link HttpRequestToolOffer}), and what
 * the existing classification / confirmation / trifecta machinery makes of them - the user-visible
 * consequence of the safe-mode split, checked against the real {@link BuiltInToolClassifications}
 * and the real {@link AgentToolGovernance#decide} rather than a restatement of them.
 */
class HttpRequestToolOfferTest {

    private static final UUID AGENT = UUID.fromString("11111111-1111-1111-1111-111111111111");

    @SuppressWarnings("unchecked")
    private static <T> ObjectProvider<T> provider(T value) {
        ObjectProvider<T> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(value);
        return provider;
    }

    private static HttpRequestExecutor executor(HttpRequestSafetyConfig config) {
        HttpRequestExecutor executor =
                new HttpRequestExecutor(
                        provider(null), provider(null), provider(null), provider(null));
        if (config != null) {
            executor.setSafetyConfig(config);
        }
        return executor;
    }

    private static HttpRequestSafetyConfig config(
            boolean enabled, boolean safeMode, boolean writeTool, String agentHosts) {
        return new HttpRequestSafetyConfig(
                enabled,
                safeMode,
                writeTool,
                false,
                65_536,
                1_048_576,
                3,
                "application/json,text/*",
                "authorization,cookie",
                "",
                agentHosts,
                true);
    }

    private static List<String> names(List<ToolCallback> callbacks) {
        return callbacks.stream().map(c -> c.getToolDefinition().name()).toList();
    }

    // ------------------------------------------------------------------ what is offered

    @Test
    void withoutAConfigurationBeanTheLegacyToolIsOfferedExactlyAsBefore() {
        List<ToolCallback> offered = HttpRequestToolOffer.callbacks(executor(null), AGENT);

        assertEquals(List.of("http_request"), names(offered));
        assertInstanceOf(HttpRequestToolCallback.class, offered.get(0));
        assertTrue(
                offered.get(0).getToolDefinition().description().contains("{{secret:"),
                "the legacy tool still documents the inline vault syntax");
    }

    @Test
    void theDefaultsKeepTheLegacyToolBecauseTheToolPredatesTheFlag() {
        List<ToolCallback> offered =
                HttpRequestToolOffer.callbacks(executor(config(true, false, true, "")), AGENT);

        assertEquals(List.of("http_request"), names(offered));
        assertInstanceOf(HttpRequestToolCallback.class, offered.get(0));
    }

    @Test
    void disablingTheToolOffersNothingEvenInSafeMode() {
        assertTrue(
                HttpRequestToolOffer.callbacks(executor(config(false, false, true, "")), AGENT)
                        .isEmpty());
        assertTrue(
                HttpRequestToolOffer.callbacks(executor(config(false, true, true, "")), AGENT)
                        .isEmpty());
    }

    @Test
    void safeModeSplitsTheToolIntoAReadToolAndAnApprovalGatedWriteTool() {
        List<ToolCallback> offered =
                HttpRequestToolOffer.callbacks(executor(config(true, true, true, "")), AGENT);

        assertEquals(List.of("http_request", "http_request_write"), names(offered));
        for (ToolCallback callback : offered) {
            assertInstanceOf(
                    SanitizedMcpToolCallback.class,
                    callback,
                    "safe-mode results go through the platform's third-party-result sanitizer");
            assertFalse(callback.getToolDefinition().description().contains("{{"));
        }
    }

    @Test
    void theWriteToolCanBeSwitchedOffLeavingOnlyTheReadTool() {
        List<ToolCallback> offered =
                HttpRequestToolOffer.callbacks(executor(config(true, true, false, "")), AGENT);

        assertEquals(List.of("http_request"), names(offered));
    }

    @Test
    void eachAgentGetsItsOwnHostAllowListFromConfiguration() {
        UUID restricted = UUID.randomUUID();
        HttpRequestExecutor executor =
                executor(config(true, true, true, restricted + "=api.allowed.example"));

        ToolCallback forRestricted = HttpRequestToolOffer.callbacks(executor, restricted).get(0);
        String refused = forRestricted.call("{\"url\":\"https://203.0.113.50/anything\"}");

        assertTrue(
                refused.startsWith("Could not complete GET https://203.0.113.50/anything: "),
                refused);
        assertTrue(refused.contains("allowed hosts"), refused);
    }

    // ------------------------------------------------------------------ classification and the
    // gate

    private static ToolClassificationRegistry registry() {
        return new ToolClassificationRegistry(
                mock(ToolClassificationRepository.class), new SimpleMeterRegistry());
    }

    private static AgentToolGovernance governance(
            ToolClassificationRegistry registry, boolean confirmationGate) {
        return new AgentToolGovernance(
                provider(registry),
                provider((MeterRegistry) new SimpleMeterRegistry()),
                provider(null),
                confirmationGate,
                "channel_send_message,run_code,run_in_code_workspace,browser_click,browser_type,google_gmail,google_calendar");
    }

    @Test
    void theWriteToolIsDeclaredEgressDurableAndIrreversibleSoItNeedsHumanConfirmation() {
        ToolClassification write = BuiltInToolClassifications.all().get("http_request_write");

        assertEquals(ToolSensitivity.EGRESS, write.sensitivity());
        assertTrue(write.irreversible());
        assertTrue(write.requiresHumanConfirmation());
        assertEquals(ToolTrustTier.UNTRUSTED_EXTERNAL, write.trust());
        assertTrue(write.requiresIdempotencyKey());
    }

    @Test
    void theReadToolIsDeclaredLikeFetchWebPage() {
        ToolClassification read = BuiltInToolClassifications.all().get("http_request");
        ToolClassification fetch = BuiltInToolClassifications.all().get("fetch_web_page");

        assertEquals(fetch.sensitivity(), read.sensitivity());
        assertEquals(fetch.trust(), read.trust());
        assertEquals(fetch.irreversible(), read.irreversible());
        assertFalse(read.requiresHumanConfirmation());
    }

    @Test
    void theConfirmationGatePromptsForEveryWriteCallByDefaultButNeverForReads() {
        ToolClassificationRegistry registry = registry();
        AgentToolGovernance gateOn = governance(registry, true);

        assertEquals(
                ToolDispatchGate.Outcome.REQUIRE_APPROVAL,
                gateOn.decide(registry, "http_request_write").outcome());
        assertEquals(
                ToolDispatchGate.Outcome.ALLOW, gateOn.decide(registry, "http_request").outcome());
        // With the gate in shadow mode the write is only counted - the documented opt-out.
        assertEquals(
                ToolDispatchGate.Outcome.ALLOW,
                governance(registry, false).decide(registry, "http_request_write").outcome());
    }

    @Test
    void theSplitMakesTheTrifectaDetectorSeeTheEgressLegOnlyWhenTheWriteToolIsHeld() {
        ToolClassificationRegistry registry = registry();
        List<String> readOnlyAgent = List.of("search_knowledge_base", "http_request");
        List<String> writingAgent =
                List.of("search_knowledge_base", "http_request", "http_request_write");

        Map<String, ToolClassificationDecision> readDecisions = registry.resolveAll(readOnlyAgent);
        Map<String, ToolClassificationDecision> writeDecisions = registry.resolveAll(writingAgent);

        LethalTrifectaDetector.Report readReport =
                LethalTrifectaDetector.analyseDecisions(readDecisions);
        LethalTrifectaDetector.Report writeReport =
                LethalTrifectaDetector.analyseDecisions(writeDecisions);

        assertFalse(
                readReport.isLethalTrifecta(),
                "private read + untrusted web read, but nothing can send");
        assertTrue(
                readReport.untrustedContentTools().contains("http_request"),
                "the read tool is an untrusted-input source");
        assertTrue(writeReport.isLethalTrifecta(), "adding the write tool completes the trifecta");
        assertEquals(List.of("http_request_write"), writeReport.egressTools());
    }
}
