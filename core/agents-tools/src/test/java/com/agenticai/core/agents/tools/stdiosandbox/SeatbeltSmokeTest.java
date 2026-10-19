package com.agenticai.core.agents.tools.stdiosandbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Runs the REAL macOS {@code sandbox-exec} with the generated profile. Skipped (assumption) on any
 * host where Seatbelt is absent or cannot be applied - including when the build itself already runs
 * inside an OS sandbox, which forbids a nested one.
 */
class SeatbeltSmokeTest {

    @TempDir Path tmp;

    private Path home;
    private Path data;
    private FakeHost host;
    private StdioSandboxProperties props;

    @BeforeEach
    void setUp() throws Exception {
        HostProbe.Real real = new HostProbe.Real();
        assumeTrue(real.os().equals("mac"), "macOS only");
        assumeTrue(
                real.runsOk(
                        List.of("/usr/bin/sandbox-exec", "-p", "(version 1)(allow default)", "/usr/bin/true"), 15),
                "sandbox-exec cannot be applied here");
        home = Files.createDirectories(tmp.resolve("home")).toRealPath();
        data = home.resolve("data");
        host = FakeHost.mac();
        host.home = home;
        host.probe = argv -> real.runsOk(argv, 15);
        props = new StdioSandboxProperties();
        props.setSandbox(StdioSandboxMode.OS);
    }

    private record Result(int exit, String out) {}

    private Result run(String serverName, String script) throws Exception {
        SandboxLaunch l =
                new StdioSandboxService(props, host, true)
                        .wrap(serverName, "/bin/sh", List.of("-c", script), Map.of("SECRET_TOKEN", "t"), data)
                        .orElseThrow();
        List<String> argv = new ArrayList<>();
        argv.add(l.command());
        argv.addAll(l.args());
        ProcessBuilder pb = new ProcessBuilder(argv);
        pb.environment().clear();
        pb.environment().putAll(l.env());
        pb.environment().put("PATH", "/usr/bin:/bin");
        pb.redirectErrorStream(true);
        Process p = pb.start();
        byte[] out = p.getInputStream().readAllBytes();
        assertThat(p.waitFor(30, TimeUnit.SECONDS)).isTrue();
        return new Result(p.exitValue(), new String(out, StandardCharsets.UTF_8));
    }

    @Test
    void childRunsAndSeesOnlyTheConfinedEnvironment() throws Exception {
        Result r = run("srv", "echo \"home=$HOME token=$SECRET_TOKEN\"");
        assertThat(r.exit()).as(r.out()).isZero();
        assertThat(r.out()).contains("home=" + data + "/home").contains("token=t");
    }

    @Test
    void writesInsideTheDataDirWorkAndEverywhereElseIsDenied() throws Exception {
        Path outside = tmp.resolve("outside.txt");
        Result r = run("srv", "echo ok > \"" + data + "/home/inside.txt\" && echo ok > \"" + outside + "\"");
        assertThat(r.exit()).as("write outside must fail: " + r.out()).isNotZero();
        assertThat(data.resolve("home/inside.txt")).exists();
        assertThat(outside).doesNotExist();
    }

    @Test
    void credentialDirectoriesCannotBeRead() throws Exception {
        Path ssh = Files.createDirectories(home.resolve(".ssh"));
        Files.writeString(ssh.resolve("id_rsa"), "PRIVATE");
        Path readable = Files.writeString(tmp.resolve("readable.txt"), "hello");

        Result allowed = run("srv", "cat \"" + readable + "\"");
        assertThat(allowed.exit()).as(allowed.out()).isZero();
        assertThat(allowed.out()).contains("hello");

        Result denied = run("srv", "cat \"" + ssh.resolve("id_rsa") + "\"");
        assertThat(denied.exit()).isNotZero();
        assertThat(denied.out()).doesNotContain("PRIVATE");
    }

    @Test
    void networkIsDeniedUnlessTheServerIsAllowListed() throws Exception {
        assumeTrue(Files.isExecutable(Path.of("/usr/bin/nc")), "nc not available");
        try (ServerSocket server = new ServerSocket(0, 5, InetAddress.getLoopbackAddress())) {
            String probe = "/usr/bin/nc -z -w 3 127.0.0.1 " + server.getLocalPort();

            Result denied = run("srv", probe);
            assertThat(denied.exit()).as("network must be off: " + denied.out()).isNotZero();

            props.setSandboxNetworkAllow(List.of("srv"));
            // Network allowed is outbound TCP only; loopback needs its own opt-in.
            Result loopbackRefused = run("srv", probe);
            assertThat(loopbackRefused.exit()).as("loopback must stay refused: " + loopbackRefused.out()).isNotZero();

            props.setSandboxAllowLoopback(true);
            Result allowed = run("srv", probe);
            assertThat(allowed.exit()).as(allowed.out()).isZero();
            // a server NOT on the list is still denied
            assertThat(run("other", probe).exit()).isNotZero();
        }
    }

    @Test
    void anAllowedNetworkDoesNotReachUnixSockets() throws Exception {
        assumeTrue(Files.isExecutable(Path.of("/usr/bin/nc")), "nc not available");
        Path dir = Files.createDirectories(tmp.resolve("socks")).toRealPath();
        Path sock = dir.resolve("agent.sock");
        try (var server = java.nio.channels.ServerSocketChannel.open(java.net.StandardProtocolFamily.UNIX)) {
            server.bind(java.net.UnixDomainSocketAddress.of(sock));
            props.setSandboxNetworkAllow(List.of("srv"));
            props.setSandboxAllowLoopback(true);
            Result r = run("srv", "/usr/bin/nc -U -w 2 \"" + sock + "\" < /dev/null");
            assertThat(r.exit()).as("unix socket connect must be refused: " + r.out()).isNotZero();
        }
    }

    @Test
    void otherRegistrationsDataUnderTheSharedRootCannotBeRead() throws Exception {
        Path root = Files.createDirectories(tmp.resolve("shared-cache")).toRealPath();
        Path other = Files.createDirectories(root.resolve("npm/2/sandbox"));
        Path secret = Files.writeString(other.resolve("token.txt"), "OTHER-TENANT");
        Path mine = root.resolve("npm/1/sandbox");
        SandboxLaunch l =
                new StdioSandboxService(props, host, true)
                        .wrap("srv", "/bin/sh", List.of("-c", "cat \"" + secret + "\"; echo own > \"" + mine + "/home/x\""),
                                Map.of(), mine, root)
                        .orElseThrow();
        List<String> argv = new ArrayList<>();
        argv.add(l.command());
        argv.addAll(l.args());
        ProcessBuilder pb = new ProcessBuilder(argv);
        pb.environment().clear();
        pb.environment().putAll(l.env());
        pb.redirectErrorStream(true);
        Process p = pb.start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(p.waitFor(30, TimeUnit.SECONDS)).isTrue();
        assertThat(out).doesNotContain("OTHER-TENANT");
        assertThat(mine.resolve("home/x")).as("its own data dir is still writable").exists();
    }
}
