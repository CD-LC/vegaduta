package com.agenticai.core.codeintel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

/**
 * Pure unit tests for the code-intel tool callbacks - tool definitions,
 * required-argument validation, and delegation to the client - with a
 * capturing {@link CodeIntelClient} subclass so no HTTP happens. The client
 * is constructed with a real {@link RestClient.Builder} (it only builds, it
 * never calls out here).
 */
class CodeIntelToolCallbackTest {

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
        public String searchCode(String query, String repoId, int topK) {
            last.set("search:" + query + ":" + repoId + ":" + topK);
            return "{\"passages\":[]}";
        }

        @Override
        public String callers(String repoId, String symbol) {
            last.set("callers:" + repoId + ":" + symbol);
            return "[]";
        }

        @Override
        public String impact(String repoId, String symbol, int depth) {
            last.set("impact:" + repoId + ":" + symbol + ":" + depth);
            return "{\"impacted\":[]}";
        }
    }

    @Test
    void toolDefinitionsHaveExpectedNames() {
        CapturingClient c = new CapturingClient();
        assertEquals("search_code", new SearchCodeToolCallback(c).getToolDefinition().name());
        assertEquals("code_callers", new CodeCallersToolCallback(c).getToolDefinition().name());
        assertEquals("code_impact", new CodeImpactToolCallback(c).getToolDefinition().name());
    }

    @Test
    void searchCodeDelegatesAndDefaultsToAllRepos() {
        CapturingClient c = new CapturingClient();
        String out = new SearchCodeToolCallback(c).call("{\"query\":\"where is retry\"}");
        assertTrue(out.contains("passages"));
        assertEquals("search:where is retry:null:6", c.last.get());
    }

    @Test
    void searchCodeScopesToRepoWhenGiven() {
        CapturingClient c = new CapturingClient();
        new SearchCodeToolCallback(c).call("{\"query\":\"q\",\"repo_id\":\"billing\"}");
        assertEquals("search:q:billing:6", c.last.get());
    }

    @Test
    void searchCodeRequiresQuery() {
        CapturingClient c = new CapturingClient();
        assertTrue(new SearchCodeToolCallback(c).call("{}").contains("query is required"));
    }

    @Test
    void callersRequiresBothArgs() {
        CapturingClient c = new CapturingClient();
        assertTrue(new CodeCallersToolCallback(c).call("{\"symbol\":\"f\"}").contains("repo_id is required"));
        assertTrue(new CodeCallersToolCallback(c).call("{\"repo_id\":\"r\"}").contains("symbol is required"));
    }

    @Test
    void callersDelegates() {
        CapturingClient c = new CapturingClient();
        new CodeCallersToolCallback(c).call("{\"symbol\":\"charge\",\"repo_id\":\"billing\"}");
        assertEquals("callers:billing:charge", c.last.get());
    }

    @Test
    void impactClampsDepthAndDelegates() {
        CapturingClient c = new CapturingClient();
        new CodeImpactToolCallback(c).call("{\"symbol\":\"X\",\"repo_id\":\"r\",\"depth\":99}");
        assertEquals("impact:r:X:6", c.last.get());   // clamped to MAX_DEPTH
        new CodeImpactToolCallback(c).call("{\"symbol\":\"X\",\"repo_id\":\"r\"}");
        assertEquals("impact:r:X:3", c.last.get());   // default depth
    }
}
