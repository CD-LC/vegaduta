package com.agenticai.core.agents.tools.localshell;

import static org.assertj.core.api.Assertions.assertThat;

import com.agenticai.core.agents.tools.stdiosandbox.StdioSandboxMode;
import com.agenticai.core.agents.tools.stdiosandbox.StdioSandboxProperties;
import com.agenticai.core.agents.tools.stdiosandbox.StdioSandboxService;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.mock.env.MockEnvironment;

class LocalShellToolSourceTest {

    private static StdioSandboxService sandbox(StdioSandboxMode mode) {
        StdioSandboxProperties props = new StdioSandboxProperties();
        props.setSandbox(mode);
        return new StdioSandboxService(props, false);
    }

    private static LocalShellToolSource source(boolean enabled, boolean ack, String profile, StdioSandboxMode mode) {
        MockEnvironment env = new MockEnvironment();
        if (profile != null) {
            env.setActiveProfiles(profile);
        }
        return new LocalShellToolSource(sandbox(mode), env, enabled, ack, "/tmp/vd-ws-test", 60, 300, 20_000, "", "", true);
    }

    private static LocalShellToolSource runnerSource(String profile, String secret) {
        MockEnvironment env = new MockEnvironment();
        if (profile != null) {
            env.setActiveProfiles(profile);
        }
        return new LocalShellToolSource(
                sandbox(StdioSandboxMode.NONE), env, true, false, "/workspace", 60, 1800, 20_000,
                "http://workspace-runner:8090", secret, true);
    }

    @Test
    void aWorkspaceRunnerReplacesTheOsSandbox() {
        LocalShellToolSource s = runnerSource("personal", "s3cret");
        assertThat(s.refusal()).isNull();
        assertThat(s.rawTools()).extracting(t -> t.getToolDefinition().name()).containsExactly("local_shell_read", "local_shell");
        assertThat(s.rawTools().get(1).getToolDefinition().description()).contains("build container").contains("/workspace");
    }

    @Test
    void aWorkspaceRunnerStillNeedsASingleUserInstallAndASecret() {
        assertThat(runnerSource(null, "s3cret").refusal()).contains("single-user");
        assertThat(runnerSource("personal", " ").refusal()).contains("no secret");
    }

    @Test
    void offByDefaultAndSaysWhy() {
        LocalShellToolSource s = source(false, false, "personal", StdioSandboxMode.OS);
        assertThat(s.rawTools()).isEmpty();
        assertThat(s.refusal()).contains("switched off");
    }

    @Test
    void neverOfferedOnASharedHostUnlessTheOwnerAcknowledgesIt() {
        assertThat(source(true, false, null, StdioSandboxMode.OS).refusal()).contains("single-user");
        assertThat(source(true, false, null, StdioSandboxMode.OS).rawTools()).isEmpty();
        // acknowledged: the next gate (the sandbox) decides
        assertThat(source(true, true, null, StdioSandboxMode.NONE).refusal()).contains("sandbox is off");
    }

    @Test
    void neverOfferedWithoutTheSandbox() {
        LocalShellToolSource s = source(true, false, "personal", StdioSandboxMode.NONE);
        assertThat(s.rawTools()).isEmpty();
        assertThat(s.refusal()).contains("sandbox is off");
    }

    @Test
    void offersBothToolsWhenEverythingHolds() {
        LocalShellToolSource s = source(true, false, "personal", StdioSandboxMode.OS);
        if (s.refusal() != null) {
            // This host has no usable OS sandbox (e.g. Linux without bubblewrap): the refusal must name it.
            assertThat(s.refusal()).containsAnyOf("unavailable", "confine");
            return;
        }
        List<ToolCallback> tools = s.rawTools();
        assertThat(tools).extracting(t -> t.getToolDefinition().name()).containsExactly("local_shell_read", "local_shell");
    }
}
