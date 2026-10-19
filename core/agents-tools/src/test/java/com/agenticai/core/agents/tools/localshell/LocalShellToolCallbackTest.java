package com.agenticai.core.agents.tools.localshell;

import static org.assertj.core.api.Assertions.assertThat;

import com.agenticai.core.agents.tools.stdiosandbox.StdioSandboxException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class LocalShellToolCallbackTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final class FakeRunner implements ShellRunner {
        final List<String> commands = new ArrayList<>();
        final List<Duration> timeouts = new ArrayList<>();
        Result next = new Result(0, "hello\n", false, false);
        RuntimeException failure;

        @Override
        public String workspace() {
            return "/work";
        }

        @Override
        public Result run(String command, Duration timeout) {
            if (failure != null) {
                throw failure;
            }
            commands.add(command);
            timeouts.add(timeout);
            return next;
        }
    }

    private static JsonNode json(String s) throws Exception {
        return MAPPER.readTree(s);
    }

    @Test
    void namesAndDescriptionsDifferByMode() {
        FakeRunner runner = new FakeRunner();
        var read = new LocalShellToolCallback(runner, true, 60, 300).getToolDefinition();
        var run = new LocalShellToolCallback(runner, false, 60, 300).getToolDefinition();
        assertThat(read.name()).isEqualTo("local_shell_read");
        assertThat(run.name()).isEqualTo("local_shell");
        assertThat(run.description()).contains("approval").contains("/work");
        assertThat(read.description()).contains("without asking");
        assertThat(run.inputSchema()).contains("\"command\"");
    }

    @Test
    void runModeRunsAnythingAndReturnsStructuredOutput() throws Exception {
        FakeRunner runner = new FakeRunner();
        JsonNode out = json(new LocalShellToolCallback(runner, false, 60, 300).call("{\"command\":\"echo hi > f && cat f\"}"));
        assertThat(runner.commands).containsExactly("echo hi > f && cat f");
        assertThat(out.path("exit_code").asInt()).isZero();
        assertThat(out.path("output").asText()).isEqualTo("hello\n");
        assertThat(out.path("workspace").asText()).isEqualTo("/work");
        assertThat(out.has("error")).isFalse();
    }

    @Test
    void readModeRefusesAnythingThatIsNotReadOnlyAndPointsAtTheOtherTool() throws Exception {
        FakeRunner runner = new FakeRunner();
        JsonNode out = json(new LocalShellToolCallback(runner, true, 60, 300).call("{\"command\":\"rm notes.txt\"}"));
        assertThat(runner.commands).isEmpty();
        assertThat(out.path("error").asText()).contains("local_shell").contains("asked");
    }

    @Test
    void readModeRunsInspectionCommands() throws Exception {
        FakeRunner runner = new FakeRunner();
        new LocalShellToolCallback(runner, true, 60, 300).call("{\"command\":\"ls -la | head\"}");
        assertThat(runner.commands).containsExactly("ls -la | head");
    }

    @Test
    void refusedCommandsNeverReachTheRunnerInEitherMode() throws Exception {
        FakeRunner runner = new FakeRunner();
        for (boolean readOnly : new boolean[] {true, false}) {
            JsonNode out = json(new LocalShellToolCallback(runner, readOnly, 60, 300).call("{\"command\":\"sudo rm -rf /\"}"));
            assertThat(out.path("error").asText()).contains("not allowed");
        }
        assertThat(runner.commands).isEmpty();
    }

    @Test
    void missingOrBlankCommandIsAnError() throws Exception {
        FakeRunner runner = new FakeRunner();
        var tool = new LocalShellToolCallback(runner, false, 60, 300);
        assertThat(json(tool.call("{}")).path("error").asText()).contains("required");
        assertThat(json(tool.call("{\"command\":\"  \"}")).path("error").asText()).contains("required");
        assertThat(json(tool.call("not json")).has("error")).isTrue();
    }

    @Test
    void timeoutIsDefaultedAndCapped() {
        FakeRunner runner = new FakeRunner();
        var tool = new LocalShellToolCallback(runner, false, 60, 120);
        tool.call("{\"command\":\"a\"}");
        tool.call("{\"command\":\"b\",\"timeout_seconds\":9999}");
        tool.call("{\"command\":\"c\",\"timeout_seconds\":0}");
        assertThat(runner.timeouts).containsExactly(Duration.ofSeconds(60), Duration.ofSeconds(120), Duration.ofSeconds(1));
    }

    @Test
    void timedOutRunSaysSo() throws Exception {
        FakeRunner runner = new FakeRunner();
        runner.next = new ShellRunner.Result(-1, "partial", false, true);
        JsonNode out = json(new LocalShellToolCallback(runner, false, 5, 5).call("{\"command\":\"sleep 99\"}"));
        assertThat(out.path("timed_out").asBoolean()).isTrue();
        assertThat(out.path("note").asText()).contains("5 s");
    }

    @Test
    void sandboxRefusalAndFailuresBecomeErrorsNotExceptions() throws Exception {
        FakeRunner runner = new FakeRunner();
        var tool = new LocalShellToolCallback(runner, false, 60, 300);
        runner.failure = new StdioSandboxException("The terminal is off: sandbox unavailable");
        assertThat(json(tool.call("{\"command\":\"ls\"}")).path("error").asText()).contains("terminal is off");
        runner.failure = new IllegalStateException("secret internals");
        assertThat(json(tool.call("{\"command\":\"ls\"}")).path("error").asText()).doesNotContain("secret internals");
    }

    @Test
    void confirmationCardShowsTheExactCommand() {
        var tool = new LocalShellToolCallback(new FakeRunner(), false, 60, 300);
        assertThat(tool.describeForConfirmation("{\"command\":\"make build\"}")).contains("make build").contains("/work");
        assertThat(tool.describeForConfirmation("{\"command\":\"mvn -q package\",\"folder\":\"demo\"}")).contains("/work/demo");
        assertThat(tool.describeForConfirmation("{}")).isNull();
        assertThat(tool.describeForConfirmation("garbage")).isNull();
        String long600 = "x".repeat(900);
        assertThat(tool.describeForConfirmation("{\"command\":\"" + long600 + "\"}")).endsWith("...");
    }

    @Test
    void aLeadingCdIntoTheSameFolderIsDropped() {
        assertThat(LocalShellToolCallback.withoutRedundantCd("cd greeter && mvn -q package", "greeter")).isEqualTo("mvn -q package");
        assertThat(LocalShellToolCallback.withoutRedundantCd("cd ./greeter/; ls", "greeter")).isEqualTo("ls");
        assertThat(LocalShellToolCallback.withoutRedundantCd("cd 'greeter' && ls", "greeter")).isEqualTo("ls");
        assertThat(LocalShellToolCallback.withoutRedundantCd("cd other && ls", "greeter")).isEqualTo("cd other && ls");
        assertThat(LocalShellToolCallback.withoutRedundantCd("cd greeter-2 && ls", "greeter")).isEqualTo("cd greeter-2 && ls");
        assertThat(LocalShellToolCallback.withoutRedundantCd("cd greeter && ls", "")).isEqualTo("cd greeter && ls");
    }
}
