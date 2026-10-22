package com.agenticai.core.workflow;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import com.agenticai.core.agents.core.AgentService;
import com.agenticai.core.agents.hosted.FreeTierChatClientService;
import com.agenticai.core.agents.tools.AgentToolProvider;
import com.agenticai.core.agents.tools.ToolCallLogRepository;
import com.agenticai.core.agents.turn.AgentChatService;
import com.agenticai.core.agents.turn.cache.LlmResponseCacheService;
import com.agenticai.core.agents.turn.quality.QueryComplexityClassifier;
import com.agenticai.core.chat.gateway.LlmGatewayService;
import com.agenticai.core.devices.DeviceCommandService;
import com.agenticai.core.eventbus.WorkflowEventPublisher;
import com.agenticai.core.providers.ProviderCredentialResolver;
import com.agenticai.core.security.TenantLimits;
import com.agenticai.core.workflow.dto.WorkflowNodeDto;
import com.agenticai.core.workflow.output.EmailOutputService;
import com.agenticai.core.workflow.output.GoogleDriveOutputService;
import com.agenticai.core.workflow.output.PdfOutputRenderer;
import com.agenticai.core.workflow.output.TeamsOutputService;
import com.agenticai.core.workflow.output.TelegramOutputService;
import com.agenticai.core.workflow.output.WhatsAppOutputService;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

/**
 * Mechanical check that a node type declared SUPPORTED is actually dispatched.
 *
 * <p><b>Why this exists.</b> Adding a workflow node type means registering it in four unconnected
 * places: the {@link WorkflowNodeType} enum, {@link WorkflowNodeExecutor}'s dispatch switch, {@code
 * OrchestratorService#validateNodeConfig}, and {@code demo/three-verticals/validate-graphs.py}.
 * Nothing checks that those four agree, and every mismatch found so far in this repo was found by a
 * person noticing rather than by a test - most recently the validator script, which had silently
 * rejected the shipped {@code http} node type as unknown because its copy of the list was never
 * updated.
 *
 * <p>The failure this catches is a quiet one. {@code executeNode}'s switch ends in a {@code default
 * ->} arm that returns {@code ok(input, "Node type '...' is not yet executable - passed input
 * through unchanged.")}. So a supported enum constant with no dispatch arm does not error: the node
 * goes green, passes its input through, and does nothing. A workflow built on it appears to run.
 *
 * <p><b>What this test does NOT do:</b> assert anything about whether a node type works. It asserts
 * only that it is REACHED. A node type may legitimately throw here (a mock dependency returning
 * null) or come back SKIPPED (missing config) - both mean it was dispatched, which is the whole
 * question.
 */
class WorkflowNodeTypeRegistrationTest {

    /**
     * The three node types that correctly do NOT appear in executeNode's switch.
     *
     * <p>Not an exemption list to grow casually - each of these is special-cased by {@code
     * WorkflowExecutionEngine}'s own traversal because it needs the graph's adjacency, which {@code
     * executeNode} does not have: {@code loop} and {@code fan_out} resolve their single body/worker
     * node and call {@code executeNode} on THAT once per item, and {@code parallel} resolves every
     * outgoing edge and runs each branch concurrently. They reach the default arm when called
     * directly, and that is correct.
     *
     * <p>If you are about to add a fourth entry here, the question to answer first is whether your
     * node type genuinely needs the graph - because if it does not, the missing dispatch arm is the
     * bug this test exists to find.
     */
    private static final Set<String> ENGINE_SPECIAL_CASED =
            Set.of("loop", "parallel", "fan_out", "sub_flow");

    private static final String NOT_EXECUTABLE_MARKER = "is not yet executable";

    @SuppressWarnings("unchecked")
    private WorkflowNodeExecutor newExecutor() {
        return NodeExecutorTestFactory.create(
                mock(OrchestratorService.class),
                mock(AgentChatService.class),
                mock(AgentService.class),
                mock(ObjectProvider.class),
                mock(WorkflowEventPublisher.class),
                mock(EmailOutputService.class),
                mock(TelegramOutputService.class),
                mock(TeamsOutputService.class),
                mock(WhatsAppOutputService.class),
                mock(GoogleDriveOutputService.class),
                mock(PdfOutputRenderer.class),
                mock(ToolCallLogRepository.class),
                mock(DeviceCommandService.class),
                mock(AgentToolProvider.class),
                com.agenticai.core.json.JsonMappers.plain(),
                mock(ObjectProvider.class),
                mock(LlmResponseCacheService.class),
                mock(QueryComplexityClassifier.class),
                mock(FreeTierChatClientService.class),
                mock(LlmGatewayService.class),
                mock(ProviderCredentialResolver.class),
                mock(TenantLimits.class),
                mock(SubagentRunRepository.class),
                mock(ObjectProvider.class),
                mock(ObjectProvider.class),
                mock(ObjectProvider.class),
                mock(ObjectProvider.class));
    }

    @Test
    void everySupportedNodeTypeIsActuallyDispatchedByTheExecutor() {
        WorkflowNodeExecutor executor = newExecutor();
        List<String> unreached = new java.util.ArrayList<>();

        for (WorkflowNodeType type : WorkflowNodeType.values()) {
            if (!type.isSupported()) {
                continue;
            }
            String wire = type.name().toLowerCase(Locale.ROOT);
            if (ENGINE_SPECIAL_CASED.contains(wire)) {
                continue;
            }
            WorkflowNodeDto node =
                    new WorkflowNodeDto(
                            "n-" + wire, wire, Map.of(), Map.of("config", Map.of(), "label", wire));
            String summary;
            try {
                WorkflowNodeExecutor.NodeExecutionResult result =
                        executor.executeNode(
                                "wf-1", "Registration probe", "run-1", node, "{}", "system:test");
                summary = result.summary() == null ? "" : result.summary();
            } catch (RuntimeException e) {
                // Dispatched, then failed on a mock. That answers the question
                // this test asks, so it is a pass.
                continue;
            }
            if (summary.contains(NOT_EXECUTABLE_MARKER)) {
                unreached.add(wire);
            }
        }

        assertTrue(
                unreached.isEmpty(),
                "These node types are declared supported but fall through to executeNode's "
                        + "default arm, so a workflow using them would run GREEN and do NOTHING: "
                        + unreached
                        + ". Add a dispatch arm, or - if the type genuinely needs the "
                        + "graph's adjacency - special-case it in WorkflowExecutionEngine and list "
                        + "it in ENGINE_SPECIAL_CASED with the reason.");
    }

    @Test
    void theEngineSpecialCasedListOnlyNamesRealNodeTypes() {
        // Guards the other direction: a renamed or removed node type would
        // leave a stale exemption here, silently re-opening the gap above for
        // whatever new type later took that name.
        Set<String> known = new java.util.HashSet<>();
        for (WorkflowNodeType type : WorkflowNodeType.values()) {
            known.add(type.name().toLowerCase(Locale.ROOT));
        }
        for (String exempt : ENGINE_SPECIAL_CASED) {
            assertTrue(
                    known.contains(exempt),
                    "ENGINE_SPECIAL_CASED names '"
                            + exempt
                            + "', which is not a WorkflowNodeType "
                            + "any more - remove it, or this list is exempting nothing while looking "
                            + "like it exempts something");
        }
    }

    @Test
    void mediaIsRegisteredEverywhereItHasToBe() {
        // The specific type this test was written alongside, asserted directly
        // rather than only through the loop - so a regression names MEDIA
        // instead of appearing as an anonymous entry in a list.
        assertTrue(WorkflowNodeType.MEDIA.isSupported());
        assertFalse(
                ENGINE_SPECIAL_CASED.contains("media"),
                "media is a single-node operation and must be dispatched, not engine-special-cased");

        WorkflowNodeExecutor.NodeExecutionResult result =
                newExecutor()
                        .executeNode(
                                "wf-1",
                                "Registration probe",
                                "run-1",
                                new WorkflowNodeDto(
                                        "m1",
                                        "media",
                                        Map.of(),
                                        Map.of("config", Map.of(), "label", "Media")),
                                "{}",
                                "system:test");

        assertFalse(result.summary().contains(NOT_EXECUTABLE_MARKER), result.summary());
        // With a bare ObjectProvider mock there is no SidecarClient, so the
        // node reports itself unavailable - which is the dispatched path.
        assertTrue(
                result.summary().toLowerCase(Locale.ROOT).contains("not available"),
                "expected the no-sidecar path, got: " + result.summary());
    }
}
