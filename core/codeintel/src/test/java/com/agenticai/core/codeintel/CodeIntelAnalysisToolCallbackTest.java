package com.agenticai.core.codeintel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

/**
 * Unit tests for {@link CodeIntelAnalysisToolCallback} + {@link CodeIntelAnalysisTools}: tool
 * definitions, repo-scope enforcement (empty = unrestricted, non-empty = allow-list), the
 * single-scope repo_id fill-in, and dispatch to the client - with a capturing client so no HTTP
 * happens.
 */
class CodeIntelAnalysisToolCallbackTest {

    private static CodeIntelProperties props() {
        CodeIntelProperties p = new CodeIntelProperties();
        p.setEnabled(true);
        p.setBaseUrl("http://localhost:8090");
        p.setInternalToken("t");
        return p;
    }

    /**
     * Records the last GET path instead of performing HTTP - every analysis method on {@link
     * CodeIntelAnalysis} funnels through {@link CodeIntelClient#get}, so overriding it captures all
     * twelve endpoints at once.
     */
    static class CapturingClient extends CodeIntelClient {
        final AtomicReference<String> last = new AtomicReference<>();

        CapturingClient() {
            super(RestClient.builder(), props());
        }

        @Override
        Result get(String path) {
            last.set(path);
            return new Result("{\"files\":[]}", 200);
        }
    }

    private static CodeIntelAnalysisToolCallback tool(
            CodeIntelClient client, Set<String> allowed, String name) {
        return (CodeIntelAnalysisToolCallback)
                CodeIntelAnalysisTools.all(client, allowed).stream()
                        .filter(t -> t.getToolDefinition().name().equals(name))
                        .findFirst()
                        .orElseThrow();
    }

    @Test
    void allToolsHaveDefinitionsAndUniqueNames() {
        CapturingClient c = new CapturingClient();
        var tools = CodeIntelAnalysisTools.all(c, Set.of());
        assertEquals(12, tools.size());
        long unique = tools.stream().map(t -> t.getToolDefinition().name()).distinct().count();
        assertEquals(12, unique);
        for (var t : tools) {
            assertNotNull(t.getToolDefinition().description());
            assertNotNull(t.getToolDefinition().inputSchema());
        }
    }

    @Test
    void repoIdRequiredWhenUnrestricted() {
        CapturingClient c = new CapturingClient();
        String out = tool(c, Set.of(), "code_map").call("{}");
        assertTrue(out.contains("repo_id is required"), out);
    }

    @Test
    void repoIdRequiredWhenScopedToMany() {
        CapturingClient c = new CapturingClient();
        String out = tool(c, Set.of("a", "b"), "code_map").call("{}");
        assertTrue(out.contains("pass one of"), out);
    }

    @Test
    void singleScopeAgentMayOmitRepoId() {
        CapturingClient c = new CapturingClient();
        String out = tool(c, Set.of("only-repo"), "code_map").call("{}");
        assertEquals("/repos/only-repo/map?budget=16000&top_n=200", c.last.get());
        assertTrue(out.contains("files"), out);
    }

    @Test
    void disallowedRepoRejected() {
        CapturingClient c = new CapturingClient();
        String out = tool(c, Set.of("allowed"), "code_map").call("{\"repo_id\":\"other\"}");
        assertTrue(out.contains("not permitted"), out);
    }

    @Test
    void dispatchPassesArgs() {
        CapturingClient c = new CapturingClient();
        tool(c, Set.of(), "code_map").call("{\"repo_id\":\"r1\",\"budget\":8000,\"top_n\":50}");
        assertEquals("/repos/r1/map?budget=8000&top_n=50", c.last.get());
        tool(c, Set.of(), "code_references").call("{\"repo_id\":\"r1\",\"symbol\":\"Foo.bar\"}");
        assertEquals("/repos/r1/references/Foo.bar", c.last.get());
        tool(c, Set.of(), "code_api_surface").call("{\"repo_id\":\"r1\"}");
        assertEquals("/repos/r1/api-surface", c.last.get());
    }
}
