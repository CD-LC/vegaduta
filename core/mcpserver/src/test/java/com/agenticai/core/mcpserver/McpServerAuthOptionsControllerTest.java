package com.agenticai.core.mcpserver;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class McpServerAuthOptionsControllerTest {

    @Test
    void defaultsDescribeGrantKeysOnlyWithOriginEnforced() {
        var o = new McpServerAuthOptionsController(false, "", "", "tenant_id", "mcp_grant_id", "enforce", "").get();
        assertThat(o.grantKeys()).isTrue();
        assertThat(o.oauth().enabled()).isFalse();
        assertThat(o.oauth().resource()).isNull();
        assertThat(o.oauth().metadataUrl()).isNull();
        assertThat(o.origin().mode()).isEqualTo("enforce");
        assertThat(o.origin().allowed()).isEmpty();
    }

    @Test
    void oauthSettingsAreDescribedWithTheMetadataUrlAndConcreteScopes() {
        var o = new McpServerAuthOptionsController(true, "https://mcp.example.com/mcp", "https://auth.example.com",
            "tenant_id", "mcp_grant_id", "report", "https://a.example, https://b.example").get();
        assertThat(o.oauth().enabled()).isTrue();
        assertThat(o.oauth().metadataUrl()).isEqualTo("https://mcp.example.com/.well-known/oauth-protected-resource/mcp");
        assertThat(o.oauth().scopes()).isNotEmpty().noneMatch(s -> s.contains("*"));
        assertThat(o.origin().mode()).isEqualTo("report");
        assertThat(o.origin().allowed()).containsExactly("https://a.example", "https://b.example");
    }
}
