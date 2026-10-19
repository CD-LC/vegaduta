package com.agenticai.core;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

/**
 * Pins the defaults of the two action-safety flag families so a later edit cannot change today's behaviour
 * silently: {@code app.tools.mcp-annotations.*} (MCP tool-annotation classification, OFF) and
 * {@code app.tools.http-request.*} (the tool stays offered - it shipped unconditionally before the flag -
 * and the hardened safe mode is OFF). Parses application.yml directly, no Spring context, like
 * {@link SwitchedOnControlsDefaultsTest}. The matching {@code @Value} defaults live in
 * {@code McpAnnotationClassifier} and {@code HttpRequestSafetyConfig}.
 */
class ToolSafetyDefaultsTest {

    @SuppressWarnings("unchecked")
    private static String get(String... path) throws Exception {
        try (InputStream in = ToolSafetyDefaultsTest.class.getResourceAsStream("/application.yml")) {
            assertThat(in).as("application.yml on the test classpath").isNotNull();
            Object current = new Yaml().load(in);
            for (String key : path) {
                assertThat(current).as("path segment " + key).isInstanceOf(Map.class);
                current = ((Map<String, Object>) current).get(key);
            }
            return String.valueOf(current);
        }
    }

    @Test
    void mcpAnnotationClassificationIsOffByDefault() throws Exception {
        assertThat(get("app", "tools", "mcp-annotations", "enabled")).isEqualTo("${TOOL_MCP_ANNOTATIONS_ENABLED:false}");
    }

    @Test
    void httpRequestStaysOfferedAndSafeModeIsOffByDefault() throws Exception {
        assertThat(get("app", "tools", "http-request", "enabled")).isEqualTo("${TOOL_HTTP_REQUEST_ENABLED:true}");
        assertThat(get("app", "tools", "http-request", "safe-mode")).isEqualTo("${TOOL_HTTP_REQUEST_SAFE_MODE:false}");
    }

    @Test
    void safeModeIsHttpsOnlyAndCredentialsNeedAnAllowListByDefault() throws Exception {
        assertThat(get("app", "tools", "http-request", "allow-http")).isEqualTo("${TOOL_HTTP_REQUEST_ALLOW_HTTP:false}");
        assertThat(get("app", "tools", "http-request", "credentials-require-allowlist"))
            .isEqualTo("${TOOL_HTTP_REQUEST_CREDENTIALS_REQUIRE_ALLOWLIST:true}");
        assertThat(get("app", "tools", "http-request", "max-redirects")).isEqualTo("${TOOL_HTTP_REQUEST_MAX_REDIRECTS:3}");
    }

    @Test
    void theHostAllowListsDefaultToEmptyWhichMeansAnyPublicHost() throws Exception {
        assertThat(get("app", "tools", "http-request", "allowed-hosts")).isEqualTo("${TOOL_HTTP_REQUEST_ALLOWED_HOSTS:}");
        assertThat(get("app", "tools", "http-request", "agent-allowed-hosts"))
            .isEqualTo("${TOOL_HTTP_REQUEST_AGENT_ALLOWED_HOSTS:}");
    }
}
