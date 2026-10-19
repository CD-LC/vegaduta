package com.agenticai.core.agents.tools.stdiosandbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Runs the generated {@code docker run} line against a real daemon with a tiny image that is
 * already present locally (never pulls). Skipped when there is no working docker or no {@code
 * alpine:latest}.
 */
class ContainerSmokeTest {

    private static final String IMAGE = "alpine:latest";

    @TempDir Path tmp;

    private String docker;
    private Path data;
    private String uidGid;

    @BeforeEach
    void setUp() throws Exception {
        HostProbe.Real real = new HostProbe.Real();
        docker = real.which("docker").orElse(null);
        uidGid = real.uidGid();
        assumeTrue(docker != null, "docker not installed");
        assumeTrue(
                real.runsOk(List.of(docker, "version", "--format", "{{.Server.Version}}"), 20),
                "no docker daemon");
        assumeTrue(
                real.runsOk(List.of(docker, "image", "inspect", IMAGE), 20),
                IMAGE + " not present locally");
        data = Files.createDirectories(tmp.resolve("data")).toRealPath();
    }

    private String run(boolean network, String script) throws Exception {
        SandboxRequest req =
                new SandboxRequest(
                        "srv",
                        "sh",
                        List.of("-c", script),
                        Map.of("SECRET_TOKEN", "s3cr3t"),
                        data,
                        network);
        List<String> argv =
                ContainerLauncher.run(docker, req, new StdioSandboxProperties(), uidGid, IMAGE);
        assertThat(String.join(" ", argv)).doesNotContain("s3cr3t");
        ProcessBuilder pb = new ProcessBuilder(argv);
        pb.environment().put("SECRET_TOKEN", "s3cr3t");
        pb.redirectErrorStream(true);
        Process p = pb.start();
        byte[] out = p.getInputStream().readAllBytes();
        assertThat(p.waitFor(90, TimeUnit.SECONDS)).isTrue();
        return p.exitValue() + "|" + new String(out, StandardCharsets.UTF_8);
    }

    @Test
    void hardenedContainerRunsWithTheForwardedEnvAndAWritableDataMount() throws Exception {
        String r = run(false, "echo token=$SECRET_TOKEN home=$HOME; echo hi > /data/f && cat /data/f");
        assertThat(r).startsWith("0|").contains("token=s3cr3t").contains("home=/data/home").contains("hi");
        assertThat(Files.readString(data.resolve("f")).strip()).isEqualTo("hi");
    }

    @Test
    void rootFilesystemIsReadOnlyCapabilitiesAreDroppedAndNetworkIsOff() throws Exception {
        assertThat(run(false, "echo x > /etc/pwned")).doesNotStartWith("0|");
        assertThat(run(false, "grep CapEff /proc/self/status")).contains("0000000000000000");
        assertThat(run(false, "ls /sys/class/net")).contains("lo").doesNotContain("eth0");
    }
}
