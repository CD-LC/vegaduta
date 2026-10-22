package com.agenticai.core.codeintel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Unit tests for the code-intel workspace write/execute surface added alongside the read-only tools
 * {@link CodeIntelToolCallbackTest} already covers: tool definitions and required-argument
 * validation for all five new tool callbacks, delegation for the three ungated ones (create/diff/
 * delete), and {@link CodeWorkspaceResultParser}'s response classification (the piece {@link
 * RunCodeWorkspaceApprovalExecutor}/{@link ApplyCodeWorkspaceApprovalExecutor} rely on to decide
 * retryable vs. terminal failure). {@link ApplyCodeChangesToolCallback}/{@link
 * RunInCodeWorkspaceToolCallback}'s actual {@code HumanApprovalGateService#evaluate} routing is NOT
 * exercised here - that needs a full Spring-wired approval stack (policy service, request
 * repository, executor registry, recorder) that belongs in an approval-module integration test, not
 * a pure unit test of this module; what IS verified here is that both tools validate their required
 * arguments and never reach the gate service on bad input.
 */
class CodeWorkspaceToolCallbackTest {

    private static final ObjectMapper MAPPER = com.agenticai.core.json.JsonMappers.plain();

    private static CodeIntelProperties props() {
        CodeIntelProperties p = new CodeIntelProperties();
        p.setEnabled(true);
        p.setBaseUrl("http://localhost:8090");
        p.setInternalToken("t");
        return p;
    }

    /** Records the last call instead of performing HTTP. */
    static class CapturingClient extends CodeIntelClient {
        final AtomicReference<String> last = new AtomicReference<>();

        CapturingClient() {
            super(RestClient.builder(), props());
        }

        @Override
        public String createWorkspace(String repoId) {
            last.set("create:" + repoId);
            return "{\"workspace_id\":\"ws-1\",\"git\":true,\"iteration\":0}";
        }

        @Override
        public String diffWorkspace(String repoId, String workspaceId) {
            last.set("diff:" + repoId + ":" + workspaceId);
            return "{\"workspace_id\":\"" + workspaceId + "\",\"diff\":\"\"}";
        }

        @Override
        public String deleteWorkspace(String repoId, String workspaceId) {
            last.set("delete:" + repoId + ":" + workspaceId);
            return "{\"deleted\":\"" + workspaceId + "\"}";
        }
    }

    @Test
    void toolDefinitionsHaveExpectedNames() {
        CapturingClient c = new CapturingClient();
        assertEquals(
                "create_code_workspace",
                new CreateCodeWorkspaceToolCallback(c).getToolDefinition().name());
        assertEquals(
                "diff_code_workspace",
                new DiffCodeWorkspaceToolCallback(c).getToolDefinition().name());
        assertEquals(
                "delete_code_workspace",
                new DeleteCodeWorkspaceToolCallback(c).getToolDefinition().name());
    }

    @Test
    void createDelegatesToClient() {
        CapturingClient c = new CapturingClient();
        String out =
                new CreateCodeWorkspaceToolCallback(c, Set.of("billing"))
                        .call("{\"repo_id\":\"billing\"}");
        assertEquals("create:billing", c.last.get());
        assertTrue(out.contains("workspace_id"));
    }

    @Test
    void createRequiresRepoId() {
        CapturingClient c = new CapturingClient();
        assertTrue(
                new CreateCodeWorkspaceToolCallback(c).call("{}").contains("repo_id is required"));
    }

    /**
     * Migration V103 - empty allowedRepoIds (the default, single-arg constructor) means deny-all,
     * the opposite of the allowedKnowledgeCollectionIds convention - see that migration's header
     * comment.
     */
    @Test
    void createRejectsRepoOutsideAllowList() {
        CapturingClient c = new CapturingClient();
        String out =
                new CreateCodeWorkspaceToolCallback(c, Set.of("billing"))
                        .call("{\"repo_id\":\"other-repo\"}");
        assertTrue(out.contains("not permitted"));
        assertEquals(null, c.last.get());
    }

    @Test
    void createWithNoGrantedReposDeniesEverything() {
        CapturingClient c = new CapturingClient();
        String out = new CreateCodeWorkspaceToolCallback(c).call("{\"repo_id\":\"billing\"}");
        assertTrue(out.contains("not permitted"));
        assertEquals(null, c.last.get());
    }

    @Test
    void diffDelegatesAndRequiresBothArgs() {
        CapturingClient c = new CapturingClient();
        new DiffCodeWorkspaceToolCallback(c, Set.of("billing"))
                .call("{\"repo_id\":\"billing\",\"workspace_id\":\"ws-1\"}");
        assertEquals("diff:billing:ws-1", c.last.get());
        assertTrue(
                new DiffCodeWorkspaceToolCallback(c)
                        .call("{\"repo_id\":\"billing\"}")
                        .contains("workspace_id is required"));
        assertTrue(
                new DiffCodeWorkspaceToolCallback(c)
                        .call("{\"workspace_id\":\"ws-1\"}")
                        .contains("repo_id is required"));
    }

    @Test
    void deleteDelegatesAndRequiresBothArgs() {
        CapturingClient c = new CapturingClient();
        new DeleteCodeWorkspaceToolCallback(c, Set.of("billing"))
                .call("{\"repo_id\":\"billing\",\"workspace_id\":\"ws-1\"}");
        assertEquals("delete:billing:ws-1", c.last.get());
        assertTrue(
                new DeleteCodeWorkspaceToolCallback(c).call("{}").contains("repo_id is required"));
    }

    // ------------------------------------------------------------------ //
    // apply/run gate tools - argument validation only, no gate service call

    @Test
    void applyToolDefinitionRequiresFiles() {
        ApplyCodeChangesToolCallback tool = new ApplyCodeChangesToolCallback(null, Set.of("r"));
        assertEquals("apply_code_changes", tool.getToolDefinition().name());
        assertTrue(
                tool.call("{\"repo_id\":\"r\",\"workspace_id\":\"ws-1\"}")
                        .contains("files is required"));
        assertTrue(
                tool.call("{\"repo_id\":\"r\",\"workspace_id\":\"ws-1\",\"files\":[]}")
                        .contains("must contain at least one"));
        assertTrue(
                tool.call("{\"workspace_id\":\"ws-1\",\"files\":[{\"path\":\"a\"}]}")
                        .contains("repo_id is required"));
        assertTrue(
                tool.call("{\"repo_id\":\"r\",\"files\":[{\"path\":\"a\"}]}")
                        .contains("workspace_id is required"));
    }

    @Test
    void applyRejectsRepoOutsideAllowListBeforeReachingGate() {
        // gateService is deliberately null - if the repo-scope check didn't run BEFORE
        // gateService.evaluate(), this would NPE instead of returning a clean error.
        ApplyCodeChangesToolCallback tool = new ApplyCodeChangesToolCallback(null, Set.of("r"));
        String out =
                tool.call(
                        "{\"repo_id\":\"other-repo\",\"workspace_id\":\"ws-1\",\"files\":[{\"path\":\"a\",\"content\":\"hi\"}]}");
        assertTrue(out.contains("not permitted"));
    }

    @Test
    void runToolDefinitionRequiresCommand() {
        RunInCodeWorkspaceToolCallback tool = new RunInCodeWorkspaceToolCallback(null, Set.of("r"));
        assertEquals("run_in_code_workspace", tool.getToolDefinition().name());
        assertTrue(
                tool.call("{\"repo_id\":\"r\",\"workspace_id\":\"ws-1\"}")
                        .contains("command is required"));
        assertTrue(
                tool.call("{\"workspace_id\":\"ws-1\",\"command\":\"mvn test\"}")
                        .contains("repo_id is required"));
        assertTrue(
                tool.call("{\"repo_id\":\"r\",\"command\":\"mvn test\"}")
                        .contains("workspace_id is required"));
    }

    @Test
    void runRejectsRepoOutsideAllowListBeforeReachingGate() {
        // gateService is deliberately null - if the repo-scope check didn't run BEFORE
        // gateService.evaluate(), this would NPE instead of returning a clean error.
        RunInCodeWorkspaceToolCallback tool = new RunInCodeWorkspaceToolCallback(null, Set.of("r"));
        String out =
                tool.call(
                        "{\"repo_id\":\"other-repo\",\"workspace_id\":\"ws-1\",\"command\":\"mvn test\"}");
        assertTrue(out.contains("not permitted"));
    }

    // ------------------------------------------------------------------ //
    // CodeWorkspaceResultParser - the retryable-vs-terminal classification
    // RunCodeWorkspaceApprovalExecutor/ApplyCodeWorkspaceApprovalExecutor
    // rely on after an already-approved call actually reaches code-intel.

    @Test
    void parserAllowsGenuineRunResultRegardlessOfSuccess() throws Exception {
        JsonNode success = MAPPER.readTree("{\"success\":true,\"command\":\"mvn test\"}");
        JsonNode failure = MAPPER.readTree("{\"success\":false,\"command\":\"mvn test\"}");
        CodeWorkspaceResultParser.checkForFailure("run_in_code_workspace", success);
        CodeWorkspaceResultParser.checkForFailure(
                "run_in_code_workspace", failure); // a failing test run is not an executor failure
    }

    @Test
    void parserAllowsGenuineApplyResult() throws Exception {
        JsonNode applied = MAPPER.readTree("{\"written\":[\"a.txt\"],\"deleted\":[]}");
        CodeWorkspaceResultParser.checkForFailure("apply_code_changes", applied);
    }

    @Test
    void parserThrowsNonRetryableForServiceErrorEnvelope() throws Exception {
        JsonNode denied =
                MAPPER.readTree(
                        "{\"timestamp\":\"now\",\"status\":403,\"error\":\"Blocked by guardrail policy: nope\"}");
        CodeWorkspaceExecutionException ex =
                assertThrows(
                        CodeWorkspaceExecutionException.class,
                        () ->
                                CodeWorkspaceResultParser.checkForFailure(
                                        "run_in_code_workspace", denied));
        assertTrue(ex.getMessage().contains("403"));
    }

    @Test
    void parserThrowsRetryableForClientSideFallback() throws Exception {
        JsonNode transportError =
                MAPPER.readTree("{\"error\":\"code-intel request failed: connection refused\"}");
        assertThrows(
                RetryableCodeWorkspaceExecutionException.class,
                () ->
                        CodeWorkspaceResultParser.checkForFailure(
                                "run_in_code_workspace", transportError));
    }

    // ------------------------------------------------------------------ //
    // payload records round-trip through Jackson the same way the tool
    // callback (serialize) and the executor (deserialize) each expect.

    @Test
    void runPayloadRoundTrips() throws Exception {
        RunCodeWorkspaceApprovalExecutor.RunPayload payload =
                new RunCodeWorkspaceApprovalExecutor.RunPayload(
                        "billing", "ws-1", "mvn -q test", 300, 2, true);
        String json = MAPPER.writeValueAsString(payload);
        RunCodeWorkspaceApprovalExecutor.RunPayload roundTripped =
                MAPPER.readValue(json, RunCodeWorkspaceApprovalExecutor.RunPayload.class);
        assertEquals(payload, roundTripped);
    }

    @Test
    void applyPayloadRoundTrips() throws Exception {
        ApplyCodeWorkspaceApprovalExecutor.ApplyPayload payload =
                new ApplyCodeWorkspaceApprovalExecutor.ApplyPayload(
                        "billing", "ws-1", List.of(Map.of("path", "a.txt", "content", "hi")), true);
        String json = MAPPER.writeValueAsString(payload);
        ApplyCodeWorkspaceApprovalExecutor.ApplyPayload roundTripped =
                MAPPER.readValue(json, ApplyCodeWorkspaceApprovalExecutor.ApplyPayload.class);
        assertEquals(payload, roundTripped);
    }
}
