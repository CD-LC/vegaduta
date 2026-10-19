package com.agenticai.core.agents.tools.localshell;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.agenticai.core.agents.tools.stdiosandbox.StdioSandboxException;
import com.agenticai.core.agents.tools.stdiosandbox.StdioSandboxMode;
import com.agenticai.core.agents.tools.stdiosandbox.StdioSandboxProperties;
import com.agenticai.core.agents.tools.stdiosandbox.StdioSandboxService;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Runs the REAL macOS sandbox (the same Seatbelt profile the STDIO MCP servers get). Skipped on any
 * host where {@code sandbox-exec} is absent or cannot be applied, so CI on Linux without bubblewrap
 * stays green; the refusal test below needs no sandbox at all.
 */
class SandboxedShellRunnerTest {

    @TempDir Path tmp;

    private Path workspace;

    @BeforeEach
    void setUp() throws Exception {
        workspace = Files.createDirectories(tmp.resolve("work")).toRealPath();
    }

    private static void assumeSeatbelt() throws Exception {
        assumeTrue(System.getProperty("os.name", "").toLowerCase().contains("mac"), "macOS only");
        Process p =
                new ProcessBuilder("/usr/bin/sandbox-exec", "-p", "(version 1)(allow default)", "/usr/bin/true")
                        .redirectErrorStream(true)
                        .start();
        assumeTrue(p.waitFor(15, TimeUnit.SECONDS) && p.exitValue() == 0, "sandbox-exec cannot be applied here");
    }

    private SandboxedShellRunner runner() {
        StdioSandboxProperties props = new StdioSandboxProperties();
        props.setSandbox(StdioSandboxMode.OS);
        return new SandboxedShellRunner(new StdioSandboxService(props, true), workspace, 2_000);
    }

    @Test
    void refusesInsteadOfRunningUnsandboxedWhenTheSandboxIsOff() {
        StdioSandboxProperties props = new StdioSandboxProperties(); // sandbox=none
        SandboxedShellRunner off = new SandboxedShellRunner(new StdioSandboxService(props, true), workspace, 2_000);
        assertThatThrownBy(() -> off.run("echo hi", Duration.ofSeconds(5)))
                .isInstanceOf(StdioSandboxException.class)
                .hasMessageContaining("sandbox");
    }

    @Test
    void runsACommandAndReturnsItsOutputAndExitCode() throws Exception {
        assumeSeatbelt();
        ShellRunner.Result ok = runner().run("echo hello; echo oops 1>&2", Duration.ofSeconds(20));
        assertThat(ok.exitCode()).isZero();
        assertThat(ok.output()).contains("hello").contains("oops");
        assertThat(runner().run("exit 3", Duration.ofSeconds(20)).exitCode()).isEqualTo(3);
    }

    @Test
    void writesWorkInsideTheWorkspaceAndNowhereElse() throws Exception {
        assumeSeatbelt();
        Path outside = tmp.resolve("outside.txt");
        ShellRunner.Result r =
                runner().run("echo kept > made.txt && cat made.txt; echo leaked > '" + outside + "'; echo done", Duration.ofSeconds(20));
        assertThat(r.output()).contains("kept").contains("done");
        assertThat(Files.readString(workspace.resolve("made.txt"))).contains("kept");
        assertThat(outside).doesNotExist();
    }

    @Test
    void credentialFoldersAreHidden() throws Exception {
        assumeSeatbelt();
        Path ssh = Path.of(System.getProperty("user.home"), ".ssh");
        assumeTrue(Files.isDirectory(ssh), "no ~/.ssh on this machine");
        ShellRunner.Result r = runner().run("ls '" + ssh + "' 2>&1; echo rc=$?", Duration.ofSeconds(20));
        assertThat(r.output()).containsIgnoringCase("not permitted").doesNotContain("id_");
    }

    @Test
    void aRunawayCommandIsStoppedAtTheTimeout() throws Exception {
        assumeSeatbelt();
        long start = System.nanoTime();
        ShellRunner.Result r = runner().run("sleep 60", Duration.ofSeconds(1));
        assertThat(r.timedOut()).isTrue();
        assertThat(TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - start)).isLessThan(15);
    }

    @Test
    void outputIsCutAtTheCap() throws Exception {
        assumeSeatbelt();
        ShellRunner.Result r = runner().run("yes abcdefghij | head -c 100000", Duration.ofSeconds(20));
        assertThat(r.truncated()).isTrue();
        assertThat(r.output().length()).isLessThanOrEqualTo(2_000);
    }

    @Test
    void theNetworkIsOffByDefault() throws Exception {
        assumeSeatbelt();
        ShellRunner.Result r =
                runner().run("/usr/bin/curl -sS --max-time 5 https://example.com 2>&1; echo rc=$?", Duration.ofSeconds(20));
        assertThat(r.output()).doesNotContain("<html").doesNotContain("Example Domain");
        assertThat(List.of(r.output().split("rc=")).getLast().trim()).isNotEqualTo("0");
    }
}
