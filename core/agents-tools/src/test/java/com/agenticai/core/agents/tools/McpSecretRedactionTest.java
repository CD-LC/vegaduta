package com.agenticai.core.agents.tools;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.mcp.McpCatalogService;
import com.agenticai.core.mcp.McpConnectionPhase;
import com.agenticai.core.mcp.McpConnectionStatus;
import com.agenticai.core.mcp.McpRegistryService;
import com.agenticai.core.mcp.McpRemoteUrlGuard;
import com.agenticai.core.mcp.McpReviewStatus;
import com.agenticai.core.mcp.McpServerType;
import com.agenticai.core.mcp.McpStdioCommandGuard;
import com.agenticai.core.mcp.McpUsageStatus;
import com.agenticai.core.mcp.atlassian.AtlassianOAuthService;
import com.agenticai.core.mcp.dto.McpDynamicConfig;
import com.agenticai.core.mcp.dto.McpServerRegistrationResponse;
import com.agenticai.core.security.TenantContext;
import com.agenticai.core.tenancy.TenantSchemaIterator;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.ServerParameters;
import io.modelcontextprotocol.spec.McpClientTransport;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.http.HttpClient;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.ObjectProvider;

/** Secret redaction used on every persisted/logged MCP failure text (characterisation). */
class McpSecretRedactionTest extends DynamicMcpConnectionManagerTestBase {

    @Test
    void redactSecretsReplacesEveryConfiguredSecretValueOccurrence() {
        String message =
                "Connection failed: invalid API key sk-abc123, retried with sk-abc123 again";
        Map<String, String> env = Map.of("BRAVE_API_KEY", "sk-abc123");

        assertEquals(
                "Connection failed: invalid API key ***, retried with *** again",
                DynamicMcpConnectionManager.redactSecrets(message, env));
    }

    @Test
    void redactSecretsHandlesMultipleDistinctSecretValues() {
        String message = "auth failed for token-one and also for token-two";
        Map<String, String> env = Map.of("A", "token-one", "B", "token-two");

        String result = DynamicMcpConnectionManager.redactSecrets(message, env);
        assertEquals("auth failed for *** and also for ***", result);
    }

    @Test
    void redactSecretsLeavesMessageUnchangedWhenNoSecretValueAppears() {
        String message = "Connection refused: connect timed out after 60s";
        Map<String, String> env = Map.of("API_KEY", "sk-abc123");

        assertEquals(message, DynamicMcpConnectionManager.redactSecrets(message, env));
    }

    @Test
    void redactSecretsReturnsMessageUnchangedWhenSecretMapIsNullOrEmpty() {
        String message = "some failure";
        assertEquals(message, DynamicMcpConnectionManager.redactSecrets(message, null));
        assertEquals(message, DynamicMcpConnectionManager.redactSecrets(message, Map.of()));
    }

    @Test
    void redactSecretsIgnoresBlankSecretValuesRatherThanReplacingEveryCharacter() {
        // A blank env value ("") would otherwise match String#replace's empty-string
        // semantics and corrupt the message by inserting "***" between every character.
        String message = "Connection failed: timeout";
        Map<String, String> env = Map.of("EMPTY_VAR", "", "BLANK_VAR", "   ");

        assertEquals(message, DynamicMcpConnectionManager.redactSecrets(message, env));
    }

    @Test
    void redactSecretsReturnsNullMessageUnchanged() {
        assertNull(DynamicMcpConnectionManager.redactSecrets(null, Map.of("K", "v")));
    }

    // L3 hardening (2026-08-14): redactSecrets used to be exact-substring-match
    // only, missing case variations and URL-encoded occurrences of a secret.

    @Test
    void redactSecretsMatchesCaseInsensitively() {
        String message = "auth failed: token SK-ABC123 rejected";
        Map<String, String> env = Map.of("API_KEY", "sk-abc123");

        assertEquals(
                "auth failed: token *** rejected",
                DynamicMcpConnectionManager.redactSecrets(message, env));
    }

    @Test
    void redactSecretsAlsoMatchesTheDecodedFormWhenTheConfiguredSecretIsItselfUrlEncoded() {
        // A tenant-configured secret value that was copy-pasted straight out of a URL
        // (still containing %XX escapes, e.g. "a%20b%2Bc") should also redact the RAW
        // decoded credential ("a b+c") if that's the form a failing tool echoes back.
        String message = "connect failed: invalid credential a b+c";
        Map<String, String> env = Map.of("DB_PASSWORD", "a%20b%2Bc");

        assertEquals(
                "connect failed: invalid credential ***",
                DynamicMcpConnectionManager.redactSecrets(message, env));
    }

    @Test
    void redactSecretsDoesNotOverRedactTextThatMerelyResemblesButDoesNotMatchASecret() {
        String message = "auth failed: token sk-other999 rejected";
        Map<String, String> env = Map.of("API_KEY", "sk-abc123");

        // "sk-abc123" is not a substring of "sk-other999" at any case-fold, so
        // nothing here should be touched.
        assertEquals(message, DynamicMcpConnectionManager.redactSecrets(message, env));
    }

    @Test
    void redactSecretsHandlesAStderrShapedLineEchoingAConfiguredCredential() {
        // 2026-08-19 MCP-blackout follow-up sweep: buildStdioTransport's stderr log line now
        // routes through redactSecrets(line, config.env()) - this locks the stderr-shaped case
        // ("invalid API key: <value>", the exact shape a third-party CLI prints) the plumbing
        // exists for.
        assertEquals(
                "invalid API key: ***",
                DynamicMcpConnectionManager.redactSecrets(
                        "invalid API key: sk-abc123", Map.of("API_KEY", "sk-abc123")));
    }

    // L2 hardening (2026-08-14): constructStdioTransport used to fall back to
    // two dead, non-env-isolated legacy StdioClientTransport shapes if the
    // reflective lookup for EnvIsolatedStdioClientTransport failed. Against
    // the pinned SDK, construction never spawns a process (spawning happens
    // later, inside the SDK's own connect(Function) call) - so this is safe
    // and fast to exercise directly, unlike connect()/disconnect() above.
}
