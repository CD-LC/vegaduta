package com.agenticai.core;

import static org.assertj.core.api.Assertions.assertThat;

import com.agenticai.core.agents.collab.CollaborationProperties;
import com.agenticai.core.agents.generation.AgentGenerationProperties;
import com.agenticai.core.agents.tools.McpClientProperties;
import com.agenticai.core.agents.tools.openapi.OpenApiToolProperties;
import com.agenticai.core.chat.AgentTurnLimitsProperties;
import com.agenticai.core.chat.LlmRequestSnapshotProperties;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.bind.PropertySourcesPlaceholdersResolver;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;

/**
 * Package 3c-2: the limits that used to be constants in core/chat, core/agents, core/agents-tools and
 * core/agents-interop bind from the real application.yml and equal both the old literal values and the code
 * defaults a context without the yml falls back to.
 */
class ConstantsIntoSettingsDefaultsTest {

    private static StandardEnvironment applicationYml() throws Exception {
        StandardEnvironment env = new StandardEnvironment();
        List<PropertySource<?>> sources =
            new YamlPropertySourceLoader().load("application", new ClassPathResource("application.yml"));
        sources.forEach(s -> env.getPropertySources().addLast(s));
        return env;
    }

    private static <T> T bind(String prefix, Class<T> type) throws Exception {
        StandardEnvironment env = applicationYml();
        Binder binder = new Binder(
            ConfigurationPropertySources.get(env), new PropertySourcesPlaceholdersResolver(env));
        return binder.bind(prefix, type).get();
    }

    @Test
    void agentTurnLimitsBindFromTheYml() throws Exception {
        AgentTurnLimitsProperties yml = bind("app.agent-turn.limits", AgentTurnLimitsProperties.class);
        AgentTurnLimitsProperties code = new AgentTurnLimitsProperties();
        assertThat(yml.getMaxMessages()).isEqualTo(code.getMaxMessages()).isEqualTo(400);
        assertThat(yml.getMaxTotalChars()).isEqualTo(code.getMaxTotalChars()).isEqualTo(600_000);
        assertThat(yml.getMaxTools()).isEqualTo(code.getMaxTools()).isEqualTo(40);
        assertThat(yml.getMaxToolCallsPerMessage()).isEqualTo(code.getMaxToolCallsPerMessage()).isEqualTo(32);
    }

    @Test
    void requestSnapshotLimitsBindFromTheYml() throws Exception {
        LlmRequestSnapshotProperties yml = bind("app.llm.request-snapshot", LlmRequestSnapshotProperties.class);
        LlmRequestSnapshotProperties code = new LlmRequestSnapshotProperties();
        assertThat(yml.getMaxMessageChars()).isEqualTo(code.getMaxMessageChars()).isEqualTo(40_000);
        assertThat(yml.getMaxTotalChars()).isEqualTo(code.getMaxTotalChars()).isEqualTo(300_000);
        assertThat(yml.getMaxSchemaChars()).isEqualTo(code.getMaxSchemaChars()).isEqualTo(4_000);
        assertThat(yml.getMaxTools()).isEqualTo(code.getMaxTools()).isEqualTo(200);
        assertThat(yml.getMaxCallChars()).isEqualTo(code.getMaxCallChars()).isEqualTo(600);
        assertThat(yml.getTruncationMarker()).isEqualTo(code.getTruncationMarker())
            .isEqualTo("\n… [truncated for display]");
    }

    @Test
    void agentGenerationLimitsBindFromTheYml() throws Exception {
        AgentGenerationProperties yml = bind("app.agents", AgentGenerationProperties.class);
        AgentGenerationProperties code = new AgentGenerationProperties();
        assertThat(yml.getMaxTokensCeiling()).isEqualTo(code.getMaxTokensCeiling()).isEqualTo(8192);
        assertThat(yml.getInferredMaxTokensFloor()).isEqualTo(code.getInferredMaxTokensFloor()).isEqualTo(128);
    }

    @Test
    void collaborationLimitsBindFromTheYml() throws Exception {
        CollaborationProperties yml = bind("app.agents.collaboration", CollaborationProperties.class);
        CollaborationProperties code = new CollaborationProperties();
        assertThat(yml.getMaxBranches()).isEqualTo(code.getMaxBranches()).isEqualTo(8);
        assertThat(yml.getBranchTimeoutSeconds()).isEqualTo(code.getBranchTimeoutSeconds()).isEqualTo(240L);
        assertThat(yml.getMaxAnswerChars()).isEqualTo(code.getMaxAnswerChars()).isEqualTo(4000);
        assertThat(yml.getMaxRoleChars()).isEqualTo(code.getMaxRoleChars()).isEqualTo(80);
    }

    @Test
    void toolTimeoutsAndLimitsBindFromTheYml() throws Exception {
        McpClientProperties mcp = bind("app.tools.mcp", McpClientProperties.class);
        McpClientProperties mcpCode = new McpClientProperties();
        assertThat(mcp.getRequestTimeoutSeconds()).isEqualTo(mcpCode.getRequestTimeoutSeconds()).isEqualTo(60);
        assertThat(mcp.getTestTimeoutSeconds()).isEqualTo(mcpCode.getTestTimeoutSeconds()).isEqualTo(20);

        OpenApiToolProperties api = bind("app.tools.openapi", OpenApiToolProperties.class);
        OpenApiToolProperties apiCode = new OpenApiToolProperties();
        assertThat(api.getConnectTimeoutSeconds()).isEqualTo(apiCode.getConnectTimeoutSeconds()).isEqualTo(10);
        assertThat(api.getRequestTimeoutSeconds()).isEqualTo(apiCode.getRequestTimeoutSeconds()).isEqualTo(30);
        assertThat(api.getMaxResponseBytes()).isEqualTo(apiCode.getMaxResponseBytes()).isEqualTo(1_048_576);
        assertThat(api.getMaxResultChars()).isEqualTo(apiCode.getMaxResultChars()).isEqualTo(16_000);
        assertThat(api.getMaxSpecRedirects()).isEqualTo(apiCode.getMaxSpecRedirects()).isEqualTo(3);
    }
}
