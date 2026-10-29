package com.agenticai.core.workflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.approval.ApprovalEvaluation;
import com.agenticai.core.approval.ApprovalReplayGrant;
import com.agenticai.core.guardrails.taint.Spotlight;
import com.agenticai.core.knowledge.KnowledgeSearchService;
import com.agenticai.core.knowledge.dto.SearchResultResponse;
import com.agenticai.core.workflow.WorkflowNodeExecutor.NodeExecutionResult;
import com.agenticai.core.workflow.dto.WorkflowNodeDto;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;

/**
 * Characterisation of {@link WorkflowNodeExecutor} dispatch: one or more cases per node type,
 * pinning the status, output, branch and key log/summary text each handler produces. Written
 * against the
 *
 * <p>This class covers trigger/default, approval, condition, code and knowledge nodes; agent,
 * supervisor, tool and device nodes are in {@link
 * WorkflowNodeExecutorAgentNodesCharacterizationTest}, output, workflow, human-input and data nodes
 * in {@link WorkflowNodeExecutorOutputNodesCharacterizationTest}.
 */
@SuppressWarnings("unchecked")
class WorkflowNodeExecutorCharacterizationTest
        extends WorkflowNodeExecutorCharacterizationTestBase {

    // ---- trivial / default / wait ------------------------------------------------------

    @Test
    void triggerPassesInputThrough() {
        NodeExecutionResult r = run(node("trigger", Map.of()), "hello");
        assertEquals("OK", r.status());
        assertEquals("hello", r.output());
        assertEquals("Received: hello", r.summary());
    }

    @Test
    void unknownTypePassesInputThrough() {
        NodeExecutionResult r = run(node("mystery", Map.of()), "x");
        assertEquals("OK", r.status());
        assertEquals("x", r.output());
        assertEquals(
                "Node type 'mystery' is not yet executable - passed input through unchanged.",
                r.summary());
    }

    @Test
    void waitNodeFailsLoudly() {
        IllegalStateException e =
                assertThrows(IllegalStateException.class, () -> run(node("wait", Map.of()), "x"));
        assertTrue(e.getMessage().contains("only supported at the top level"));
    }

    @Test
    void typeIsMatchedCaseInsensitively() {
        NodeExecutionResult r = run(node("TRIGGER", Map.of()), "abc");
        assertEquals("Received: abc", r.summary());
    }

    // ---- approval gate -----------------------------------------------------------------

    @Test
    void approvalNodeHaltsWhenPending() {
        when(orchestrator.authorizeSensitiveNode(any(), any(), any(), any(), any(), any()))
                .thenReturn(ApprovalEvaluation.pending(UUID.randomUUID()));
        NodeExecutionResult r = run(node("approval", Map.of()), "x");
        assertEquals(WorkflowRunStatus.AWAITING_APPROVAL, r.haltRun());
        assertTrue(r.summary().startsWith("Awaiting human approval (request "));
    }

    @Test
    void approvalNodeAutoApprovedNamesThePolicy() {
        when(orchestrator.authorizeSensitiveNode(any(), any(), any(), any(), any(), any()))
                .thenReturn(ApprovalEvaluation.autoApproved());
        NodeExecutionResult r = run(node("approval", Map.of()), "x");
        assertEquals("OK", r.status());
        assertEquals("x", r.output());
        assertTrue(
                r.summary()
                        .contains("Auto-approved by policy for action 'workflow.node.approval'"));
        assertTrue(r.summary().contains("requested by tester"));
    }

    @Test
    void approvalReplayGrantResumesApprovalNode() {
        ApprovalReplayGrant.grant("workflow.node.approval");
        NodeExecutionResult r = run(node("approval", Map.of()), "x");
        assertEquals("Approved by human decision - run resumed.", r.summary());
        verify(orchestrator, never())
                .authorizeSensitiveNode(any(), any(), any(), any(), any(), any());
    }

    @Test
    void requiresApprovalOnNonApprovalNodeRunsItAfterAutoApprove() {
        when(orchestrator.authorizeSensitiveNode(any(), any(), any(), any(), any(), any()))
                .thenReturn(ApprovalEvaluation.autoApproved());
        NodeExecutionResult r =
                run(node("code", Map.of("requiresApproval", true, "operation", "uppercase")), "ab");
        assertEquals("AB", r.output());
    }

    // ---- condition ---------------------------------------------------------------------

    @Test
    void conditionContainsMatchesCaseInsensitively() {
        NodeExecutionResult r =
                run(node("condition", Map.of("contains", "REFUND")), "a refund please");
        assertEquals("true", r.branch());
        assertEquals("Condition 'REFUND' -> true", r.summary());
        assertEquals("false", run(node("condition", Map.of("contains", "zzz")), "abc").branch());
        assertEquals("false", run(node("condition", Map.of()), "abc").branch());
    }

    @Test
    void conditionExpressionMode() {
        WorkflowNodeDto n =
                node("condition", Map.of("mode", "expression", "expression", "$json.total > 1000"));
        NodeExecutionResult hit = run(n, "{\"total\":2000}");
        assertEquals("true", hit.branch());
        assertTrue(hit.summary().startsWith("Condition expression '$json.total > 1000' -> "));
        assertEquals("false", run(n, "{\"total\":5}").branch());
    }

    // ---- code / transform --------------------------------------------------------------

    @Test
    void codeOperations() {
        assertEquals("AB", run(node("code", Map.of("operation", "uppercase")), "ab").output());
        assertEquals("ab", run(node("code", Map.of("operation", "lowercase")), "AB").output());
        assertEquals(
                "Bob",
                run(
                                node("code", Map.of("operation", "extract", "field", "user.name")),
                                "{\"user\":{\"name\":\"Bob\"}}")
                        .output());
        assertEquals(
                "a-c",
                run(
                                node(
                                        "code",
                                        Map.of(
                                                "operation",
                                                "regex_replace",
                                                "pattern",
                                                "b",
                                                "replacement",
                                                "-")),
                                "abc")
                        .output()
                        .replace("a-c", "a-c"));
        assertEquals(
                "\"hi\"", run(node("code", Map.of("operation", "json_stringify")), "hi").output());
        assertEquals(
                "{\n  \"a\" : 1\n}",
                run(node("code", Map.of("operation", "json_parse")), "{\"a\":1}").output());
    }

    @Test
    void codeTemplateOperation() {
        assertEquals(
                "Hello Ann",
                run(
                                node(
                                        "code",
                                        Map.of(
                                                "operation",
                                                "template",
                                                "template",
                                                "Hello {{input}}")),
                                "Ann")
                        .output());
        // no template at all emits the input
        assertEquals("Ann", run(node("code", Map.of("operation", "template")), "Ann").output());
    }

    @Test
    void codeMisconfigurationIsSkippedNotThrown() {
        NodeExecutionResult none = run(node("code", Map.of()), "x");
        assertEquals("SKIPPED", none.status());
        assertEquals("No operation selected for this Transform node.", none.summary());
        assertEquals("x", none.output());
        NodeExecutionResult bad = run(node("code", Map.of("operation", "nope")), "x");
        assertEquals("SKIPPED", bad.status());
        assertEquals("Transform failed: Unrecognized operation 'nope'", bad.summary());
        assertEquals(
                "SKIPPED", run(node("code", Map.of("operation", "json_parse")), "{bad").status());
    }

    @Test
    void codeSuccessLogLine() {
        NodeExecutionResult r = run(node("code", Map.of("operation", "uppercase")), "ab");
        assertEquals(List.of("Applied 'uppercase' transform (2 -> 2 chars)."), r.logLines());
    }

    // ---- knowledge ---------------------------------------------------------------------

    @Test
    void knowledgeUnavailableIsSkipped() {
        NodeExecutionResult r = run(node("knowledge", Map.of()), "q");
        assertEquals("SKIPPED", r.status());
        assertEquals("q", r.output());
        assertTrue(r.summary().startsWith("Knowledge search unavailable"));
    }

    @Test
    void knowledgeSearchWrapsRetrievedContext() {
        KnowledgeSearchService svc = mock(KnowledgeSearchService.class);
        when(knowledge.getIfAvailable()).thenReturn(svc);
        SearchResultResponse a = mock(SearchResultResponse.class);
        SearchResultResponse b = mock(SearchResultResponse.class);
        when(a.content()).thenReturn("A");
        when(b.content()).thenReturn("B");
        when(svc.search(eq("q"), eq(3), eq(Set.of()))).thenReturn(List.of(a, b));
        NodeExecutionResult r = run(node("knowledge", Map.of("topK", 3)), "q");
        assertEquals("OK", r.status());
        assertTrue(r.output().startsWith("Context from knowledge base:\n"));
        assertTrue(Spotlight.hasUntrustedBlock(r.output()));
        assertTrue(r.output().contains("[1] A"));
        assertTrue(r.output().contains("[2] B"));
        assertTrue(r.output().endsWith("\n\nOriginal input: q"));
        assertEquals("2 chunk(s) retrieved.", r.summary());
        assertEquals(
                List.of(
                        "Searching knowledge base (topK=3, all collections).",
                        "2 chunk(s) retrieved."),
                r.logLines());
    }

    @Test
    void knowledgeWithNoHitsPassesInput() {
        KnowledgeSearchService svc = mock(KnowledgeSearchService.class);
        when(knowledge.getIfAvailable()).thenReturn(svc);
        when(svc.search(anyString(), ArgumentMatchers.anyInt(), any())).thenReturn(List.of());
        assertEquals("q", run(node("knowledge", Map.of()), "q").output());
    }
}
