package com.agenticai.core.agents.tools;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.agenticai.core.agents.tools.classification.McpAnnotationClassifier;
import com.agenticai.core.agents.tools.classification.ToolClassification;
import com.agenticai.core.agents.tools.classification.ToolClassificationRegistry;
import com.agenticai.core.agents.tools.classification.ToolClassificationRepository;
import com.agenticai.core.agents.tools.classification.ToolSensitivity;
import com.agenticai.core.guardrails.ToolDispatchGate;
import com.agenticai.core.security.TenantContext;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.modelcontextprotocol.spec.McpSchema;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.DefaultToolDefinition;
import org.springframework.beans.factory.ObjectProvider;

/**
 * {@link McpAnnotationCapture}: discovery-time hint capture. The load-bearing property is that the
 * key it records under is byte-for-byte the name the classification gate later asks about - the
 * name the model sees after {@code SanitizedMcpToolCallback} and {@code NamespacedMcpToolCallback}
 * have both run.
 */
class McpAnnotationCaptureTest {

    private static McpSchema.Tool tool(String name, McpSchema.ToolAnnotations annotations) {
        return new McpSchema.Tool(name, null, "a tool", null, null, annotations, null);
    }

    private static McpSchema.ToolAnnotations hints(
            Boolean readOnly, Boolean destructive, Boolean openWorld) {
        return new McpSchema.ToolAnnotations(null, readOnly, destructive, null, openWorld, null);
    }

    /**
     * What getToolDefinition().name() returns once both wrappers have run - the string the gate
     * sees.
     */
    private static String realWrappedName(String serverName, String rawToolName) {
        ToolCallback delegate = mock(ToolCallback.class);
        when(delegate.getToolDefinition())
                .thenReturn(new DefaultToolDefinition(rawToolName, "d", "{}"));
        return new NamespacedMcpToolCallback(new SanitizedMcpToolCallback(delegate), serverName)
                .getToolDefinition()
                .name();
    }

    @Test
    void theRecordedNameIsExactlyTheWrappedNameForOrdinaryAndHostileInputs() {
        List<String[]> cases =
                List.of(
                        new String[] {"github", "create_issue"},
                        new String[] {"My Server!", "do_thing"},
                        new String[] {"  ", "ping"},
                        new String[] {null, "ping"},
                        new String[] {"srv", "bad\u001b[31mname"}, // ANSI in the tool name
                        new String[] {"srv", "zero\u200Bwidth"}, // invisible character
                        new String[] {
                            "x".repeat(100), "t".repeat(200)
                        }, // clipped to MAX_NAME_CHARS
                        new String[] {"srv-é", "outil_é"});
        for (String[] c : cases) {
            assertEquals(
                    realWrappedName(c[0], c[1]),
                    NamespacedMcpToolCallback.finalToolName(c[0], c[1]),
                    "server=" + c[0] + " tool=" + c[1]);
        }
    }

    @Test
    void capturedHintsClassifyTheToolUnderItsFinalNameForThatTenantOnly() {
        McpAnnotationClassifier classifier = new McpAnnotationClassifier(true);
        McpAnnotationCapture.capture(
                classifier,
                "t1",
                "crm",
                List.of(
                        tool("delete_contact", hints(false, true, null)),
                        tool("list_contacts", hints(true, null, null)),
                        tool("untouched", null),
                        tool(
                                "title_only",
                                new McpSchema.ToolAnnotations(
                                        "Just a title", null, null, null, null, null))));

        ToolClassification delete =
                classifier.classify("t1", "mcp__crm__delete_contact").orElseThrow();
        assertTrue(delete.requiresHumanConfirmation());
        assertEquals(
                ToolSensitivity.READ_TENANT,
                classifier.classify("t1", "mcp__crm__list_contacts").orElseThrow().sensitivity());
        assertTrue(
                classifier.classify("t1", "mcp__crm__untouched").isEmpty(),
                "unannotated stays unclassified");
        assertTrue(
                classifier.classify("t1", "mcp__crm__title_only").isEmpty(),
                "a title says nothing about risk");
        assertTrue(
                classifier.classify("t2", "mcp__crm__delete_contact").isEmpty(),
                "another tenant's connection");
    }

    @Test
    void aRefreshReplacesThePreviousListSoARenamedToolLosesItsOldHints() {
        McpAnnotationClassifier classifier = new McpAnnotationClassifier(true);
        McpAnnotationCapture.capture(
                classifier, "t1", "crm", List.of(tool("old_name", hints(false, true, null))));
        McpAnnotationCapture.capture(
                classifier, "t1", "crm", List.of(tool("new_name", hints(true, null, null))));

        assertTrue(classifier.classify("t1", "mcp__crm__old_name").isEmpty());
        assertTrue(classifier.classify("t1", "mcp__crm__new_name").isPresent());
    }

    @Test
    void staticServersAreRecordedUnderTheSharedScope() {
        McpAnnotationClassifier classifier = new McpAnnotationClassifier(true);
        McpAnnotationCapture.capture(
                classifier,
                McpAnnotationClassifier.GLOBAL_SCOPE,
                "fetch",
                List.of(tool("fetch", hints(true, null, true))));

        assertTrue(classifier.classify("any-tenant", "mcp__fetch__fetch").isPresent());
    }

    @Test
    void captureIsANoOpWithoutAClassifierWhenDisabledOrGivenNothingAndNeverThrows() {
        McpAnnotationClassifier disabled = new McpAnnotationClassifier(false);
        List<McpSchema.Tool> tools = new ArrayList<>();
        tools.add(tool("x", hints(false, true, null)));
        tools.add(null);
        tools.add(tool(null, hints(false, true, null)));

        assertDoesNotThrow(() -> McpAnnotationCapture.capture(null, "t1", "s", tools));
        assertDoesNotThrow(() -> McpAnnotationCapture.capture(disabled, "t1", "s", tools));
        assertDoesNotThrow(
                () ->
                        McpAnnotationCapture.capture(
                                new McpAnnotationClassifier(true), null, "s", tools));
        assertDoesNotThrow(
                () ->
                        McpAnnotationCapture.capture(
                                new McpAnnotationClassifier(true), "t1", "s", null));
        assertFalse(disabled.classify("t1", "mcp__s__x").isPresent());

        // A null tool or a null tool name in an otherwise valid list is skipped, the rest still
        // recorded.
        McpAnnotationClassifier enabled = new McpAnnotationClassifier(true);
        McpAnnotationCapture.capture(enabled, "t1", "s", tools);
        assertTrue(enabled.classify("t1", "mcp__s__x").isPresent());
    }

    @SuppressWarnings("unchecked")
    private static <T> ObjectProvider<T> provider(T value) {
        ObjectProvider<T> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(value);
        return provider;
    }

    @Test
    void
            throughTheRealGateADestructiveAnnotatedToolNeedsApprovalWhileReadOnlyAndUnannotatedToolsRun() {
        McpAnnotationClassifier classifier = new McpAnnotationClassifier(true);
        McpAnnotationCapture.capture(
                classifier,
                "t1",
                "crm",
                List.of(
                        tool("delete_contact", hints(false, true, null)),
                        tool("list_contacts", hints(true, null, null)),
                        tool("mystery", null)));
        ToolClassificationRegistry registry =
                new ToolClassificationRegistry(
                        mock(ToolClassificationRepository.class), new SimpleMeterRegistry());
        registry.setMcpAnnotations(classifier);
        AgentToolGovernance governance =
                new AgentToolGovernance(
                        provider(registry),
                        provider((MeterRegistry) new SimpleMeterRegistry()),
                        provider(null),
                        true,
                        "");

        TenantContext.set(TenantContext.of("t1", "u", Set.of()));
        try {
            assertEquals(
                    ToolDispatchGate.Outcome.REQUIRE_APPROVAL,
                    governance.decide(registry, "mcp__crm__delete_contact").outcome());
            assertEquals(
                    ToolDispatchGate.Outcome.ALLOW,
                    governance.decide(registry, "mcp__crm__list_contacts").outcome());
            assertEquals(
                    ToolDispatchGate.Outcome.ALLOW,
                    governance.decide(registry, "mcp__crm__mystery").outcome(),
                    "an unannotated tool is exactly as before: unclassified, allowed and counted");
        } finally {
            TenantContext.clear();
        }
    }

    @Test
    void aNullListLeavesWhatWasRecordedUntouched() {
        McpAnnotationClassifier classifier = new McpAnnotationClassifier(true);
        McpAnnotationCapture.capture(
                classifier, "t1", "crm", List.of(tool("keep", hints(true, null, null))));

        McpAnnotationCapture.capture(classifier, "t1", "crm", null);

        assertTrue(classifier.classify("t1", "mcp__crm__keep").isPresent());
    }

    @Test
    void forgetDropsTheHintsOfTheToolsADisconnectedRegistrationHadExposed() {
        McpAnnotationClassifier classifier = new McpAnnotationClassifier(true);
        McpAnnotationCapture.capture(
                classifier,
                "t1",
                "crm",
                List.of(
                        tool("delete_contact", hints(false, true, null)),
                        tool("other", hints(false, true, null))));
        ToolCallback exposed = mock(ToolCallback.class);
        when(exposed.getToolDefinition())
                .thenReturn(new DefaultToolDefinition("mcp__crm__delete_contact", "d", "{}"));

        McpAnnotationCapture.forget(classifier, "t1", List.of(exposed));

        assertTrue(
                classifier.classify("t1", "mcp__crm__delete_contact").isEmpty(),
                "a stale claim must not outlive its connection");
        assertTrue(
                classifier.classify("t1", "mcp__crm__other").isPresent(),
                "only the named tools are dropped");
        assertDoesNotThrow(() -> McpAnnotationCapture.forget(null, "t1", List.of(exposed)));
        assertDoesNotThrow(() -> McpAnnotationCapture.forget(classifier, "t1", null));
    }
}
