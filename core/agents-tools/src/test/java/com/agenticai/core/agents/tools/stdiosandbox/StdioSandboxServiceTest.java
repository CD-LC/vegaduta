package com.agenticai.core.agents.tools.stdiosandbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class StdioSandboxServiceTest {

    private static StdioSandboxProperties props(StdioSandboxMode mode) {
        StdioSandboxProperties p = new StdioSandboxProperties();
        p.setSandbox(mode);
        return p;
    }

    private static StdioSandboxService service(StdioSandboxProperties p, FakeHost h) {
        return new StdioSandboxService(p, h, true);
    }

    private static Optional<SandboxLaunch> wrap(StdioSandboxService s, String name, Path dir) {
        return s.wrap(name, "npx", List.of("-y", "pkg"), Map.of("API_KEY", "k", "HOME", "/Users/x"), dir);
    }

    @Test
    void modeNoneChangesNothingAndNeverProbesTheHost(@TempDir Path dir) {
        FakeHost h = FakeHost.mac();
        StdioSandboxService s = service(props(StdioSandboxMode.NONE), h);
        assertThat(wrap(s, "srv", dir)).isEmpty();
        assertThat(s.enabled()).isFalse();
        assertThat(h.probes).isEmpty();
        StdioSandboxStatus st = s.status();
        assertThat(st.mode()).isEqualTo("none");
        assertThat(st.available()).isTrue();
        assertThat(st.enforcing()).isFalse();
    }

    @Test
    void macOsWrapsWithSandboxExecAndAnInlineProfile(@TempDir Path dir) throws Exception {
        FakeHost h = FakeHost.mac();
        h.home = dir.toRealPath();
        StdioSandboxService s = service(props(StdioSandboxMode.OS), h);

        SandboxLaunch l = wrap(s, "srv", dir.resolve("data")).orElseThrow();

        assertThat(l.command()).isEqualTo("/usr/bin/sandbox-exec");
        assertThat(l.args().get(0)).isEqualTo("-p");
        assertThat(l.args().get(1)).contains("(deny default)").doesNotContain("network");
        assertThat(l.args().subList(2, l.args().size())).containsExactly("npx", "-y", "pkg");
        assertThat(l.mechanism()).isEqualTo("seatbelt");
        assertThat(l.degraded()).isFalse();
        // writable data dir exists, with the per-purpose subdirectories
        assertThat(dir.resolve("data/home")).isDirectory();
        // env: the approved env, with platform-owned names re-pointed into the data dir
        String data = dir.resolve("data").toRealPath().toString();
        assertThat(l.env()).containsEntry("API_KEY", "k");
        assertThat(l.env()).containsEntry("HOME", data + "/home").containsEntry("TMPDIR", data + "/tmp");
        assertThat(l.env()).containsEntry("npm_config_cache", data + "/npm");
    }

    @Test
    void tenantCannotAimCachesOrHomeOutsideTheDataDirEvenWithOtherCasing(@TempDir Path dir) throws Exception {
        FakeHost h = FakeHost.mac();
        StdioSandboxService s = service(props(StdioSandboxMode.OS), h);
        SandboxLaunch l =
                s.wrap(
                                "srv",
                                "npx",
                                List.of(),
                                Map.of("NPM_CONFIG_CACHE", "/etc", "home", "/root", "Xdg_Cache_Home", "/etc", "OK", "1"),
                                dir)
                        .orElseThrow();
        String data = dir.toRealPath().toString();
        assertThat(l.env().keySet()).doesNotContain("NPM_CONFIG_CACHE", "home", "Xdg_Cache_Home");
        assertThat(l.env().values()).doesNotContain("/etc", "/root");
        assertThat(l.env()).containsEntry("npm_config_cache", data + "/npm").containsEntry("OK", "1");
    }

    @Test
    void networkIsGrantedOnlyToListedServers(@TempDir Path dir) {
        FakeHost h = FakeHost.mac();
        StdioSandboxProperties p = props(StdioSandboxMode.OS);
        p.setSandboxNetworkAllow(List.of("GitHub"));
        StdioSandboxService s = service(p, h);
        String grant = "(allow network-outbound (remote tcp))";
        String loopbackDeny = "(deny network-outbound (remote ip \"localhost:*\"))";
        assertThat(wrap(s, "github", dir).orElseThrow().args().get(1)).contains(grant).contains(loopbackDeny);
        assertThat(wrap(s, "github", dir).orElseThrow().args().get(1)).doesNotContain("(allow network*)");
        assertThat(wrap(s, "slack", dir).orElseThrow().args().get(1)).doesNotContain("network");
        p.setSandboxNetworkAllow(List.of("*"));
        assertThat(wrap(s, "anything", dir).orElseThrow().args().get(1)).contains(grant);
        p.setSandboxAllowLoopback(true);
        assertThat(wrap(s, "anything", dir).orElseThrow().args().get(1)).contains(grant).doesNotContain(loopbackDeny);
    }

    @Test
    void unavailableAndRequiredRefusesTheSpawn(@TempDir Path dir) {
        FakeHost h = FakeHost.mac();
        h.probe = argv -> false; // e.g. core itself runs inside another sandbox
        StdioSandboxService s = service(props(StdioSandboxMode.OS), h);
        assertThatThrownBy(() -> wrap(s, "srv", dir))
                .isInstanceOf(StdioSandboxException.class)
                .hasMessageContaining("refused")
                .hasMessageNotContaining(dir.toString());
        StdioSandboxStatus st = s.status();
        assertThat(st.available()).isFalse();
        assertThat(st.enforcing()).as("spawns are refused, so the sandbox is enforced").isTrue();
        assertThat(st.mechanism()).isEqualTo("none");
    }

    @Test
    void unavailableAndNotRequiredFallsBackUnsandboxed(@TempDir Path dir) {
        FakeHost h = FakeHost.mac();
        h.executables.clear();
        StdioSandboxProperties p = props(StdioSandboxMode.OS);
        p.setSandboxRequired(false);
        StdioSandboxService s = service(p, h);
        assertThat(wrap(s, "srv", dir)).isEmpty();
        assertThat(s.status().enforcing()).isFalse();
    }

    @Test
    void startupPreflightFailsFastWhenRequiredAndUnavailable() {
        FakeHost h = new FakeHost();
        h.os = "windows";
        StdioSandboxService failing = new StdioSandboxService(props(StdioSandboxMode.OS), h, true);
        assertThatThrownBy(failing::preflight)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Windows");
        // fail-fast=false downgrades to an error log
        new StdioSandboxService(props(StdioSandboxMode.OS), h, false).preflight();
        // not required: warn only
        StdioSandboxProperties optional = props(StdioSandboxMode.OS);
        optional.setSandboxRequired(false);
        new StdioSandboxService(optional, h, true).preflight();
    }

    @Test
    void windowsIsUnsupportedForOsModeButContainerModeWorks(@TempDir Path dir) {
        FakeHost h = new FakeHost();
        h.os = "windows";
        h.tools.add("docker");
        assertThat(service(props(StdioSandboxMode.OS), h).status().detail()).contains("Windows");
        StdioSandboxService c = service(props(StdioSandboxMode.CONTAINER), h);
        SandboxLaunch l = wrap(c, "srv", dir).orElseThrow();
        assertThat(l.command()).isEqualTo("/usr/bin/docker");
        assertThat(l.args()).startsWith("run", "--rm");
        assertThat(l.mechanism()).isEqualTo("docker");
        // docker CLI env: tenant values only (so -e NAME works), never host paths
        assertThat(l.env()).containsEntry("API_KEY", "k").doesNotContainKey("HOME");
    }

    @Test
    void containerModeNeedsAWorkingDaemonAndHonoursTheRuntimeChoice(@TempDir Path dir) {
        FakeHost h = new FakeHost();
        h.tools.add("docker");
        h.tools.add("podman");
        h.probe = argv -> argv.get(0).endsWith("podman");
        StdioSandboxService auto = service(props(StdioSandboxMode.CONTAINER), h);
        assertThat(wrap(auto, "s", dir).orElseThrow().mechanism()).isEqualTo("podman");

        StdioSandboxProperties dockerOnly = props(StdioSandboxMode.CONTAINER);
        dockerOnly.setSandboxContainerRuntime("docker");
        assertThatThrownBy(() -> wrap(service(dockerOnly, h), "s", dir))
                .isInstanceOf(StdioSandboxException.class);
    }

    @Test
    void linuxPrefersBubblewrapAndMasksHome(@TempDir Path dir) {
        FakeHost h = FakeHost.linuxWithBwrap();
        h.pathDirs = List.of(Path.of("/home/test/.nvm/versions/node/v22/bin"), Path.of("/usr/bin"));
        StdioSandboxService s = service(props(StdioSandboxMode.OS), h);
        SandboxLaunch l = wrap(s, "srv", dir).orElseThrow();
        assertThat(l.command()).isEqualTo("/usr/bin/bwrap");
        assertThat(l.mechanism()).isEqualTo("bubblewrap");
        assertThat(l.degraded()).isFalse();
        assertThat(l.args()).containsSequence("--tmpfs", "/home/test");
        assertThat(l.args()).containsSequence("--ro-bind-try", "/home/test/.nvm/versions/node/v22/bin");
    }

    @Test
    void linuxFallsBackToDegradedUnshareLoudlyAndOnlyWhenAllowed(@TempDir Path dir) {
        FakeHost h = new FakeHost();
        h.os = "linux";
        h.tools.add("bwrap");
        h.tools.add("unshare");
        h.executables.add("/usr/bin/true");
        h.probe = argv -> !argv.get(0).endsWith("bwrap");
        StdioSandboxService s = service(props(StdioSandboxMode.OS), h);
        SandboxLaunch l = wrap(s, "srv", dir).orElseThrow();
        assertThat(l.mechanism()).isEqualTo("unshare");
        assertThat(l.degraded()).isTrue();
        assertThat(s.status().degraded()).isTrue();
        assertThat(s.status().detail()).contains("DEGRADED").contains("NOT");

        StdioSandboxProperties strict = props(StdioSandboxMode.OS);
        strict.setSandboxAllowDegraded(false);
        assertThatThrownBy(() -> wrap(service(strict, h), "srv", dir))
                .isInstanceOf(StdioSandboxException.class);
    }

    @Test
    void anUnavailableMechanismIsReprobedAfterTheTtlButAnAvailableOneIsKept(@TempDir Path dir) {
        FakeHost h = FakeHost.mac();
        boolean[] up = {false};
        h.probe = argv -> up[0];
        long[] now = {1_000_000_000L};
        StdioSandboxService s = new StdioSandboxService(props(StdioSandboxMode.OS), h, true, () -> now[0]);

        assertThat(s.status().available()).isFalse();
        int probesAfterFirst = h.probes.size();
        up[0] = true; // e.g. the container daemon / sandbox became usable after core started
        now[0] += java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
        assertThat(s.status().available()).as("cached inside the TTL").isFalse();
        assertThat(h.probes).hasSize(probesAfterFirst);

        now[0] += java.util.concurrent.TimeUnit.SECONDS.toNanos(40);
        assertThat(s.status().available()).as("re-probed after the TTL").isTrue();
        assertThat(wrap(s, "srv", dir)).isPresent();

        int probesWhenUp = h.probes.size();
        up[0] = false;
        now[0] += java.util.concurrent.TimeUnit.HOURS.toNanos(1);
        assertThat(s.status().available()).as("an available result is kept").isTrue();
        assertThat(h.probes).hasSize(probesWhenUp);
    }

    @Test
    void thePrivateRootIsMaskedFromTheMacProfileButTheDataDirStaysReadable(@TempDir Path dir) throws Exception {
        Path root = Files.createDirectories(dir.resolve("cache")).toRealPath();
        StdioSandboxService s = service(props(StdioSandboxMode.OS), FakeHost.mac());
        SandboxLaunch l =
                s.wrap("srv", "npx", List.of(), Map.of(), root.resolve("npm/1/sandbox"), root).orElseThrow();
        String profile = l.args().get(1);
        int deny = profile.indexOf("(deny file-read* (subpath \"" + root + "\"))");
        int allow = profile.indexOf("(allow file-read* (subpath \"" + root + "/npm/1/sandbox\"))");
        assertThat(deny).isGreaterThanOrEqualTo(0);
        assertThat(allow).as("the data dir is re-allowed AFTER the mask (last rule wins)").isGreaterThan(deny);
    }

    @Test
    void unshareProbeUsesTheSameFlagsAsTheSpawn() {
        FakeHost h = new FakeHost();
        h.os = "linux";
        h.tools.add("unshare");
        h.executables.add("/usr/bin/true");
        List<List<String>> seen = h.probes;
        h.probe = argv -> true;
        new StdioSandboxService(props(StdioSandboxMode.OS), h, true).status();
        assertThat(seen).anySatisfy(argv -> assertThat(argv).contains("--kill-child", "--ipc", "--uts"));
    }

    @Test
    void aSymlinkedDataDirIsRefused(@TempDir Path dir) throws Exception {
        Path target = Files.createDirectory(dir.resolve("real"));
        Path link = Files.createSymbolicLink(dir.resolve("link"), target);
        StdioSandboxService s = service(props(StdioSandboxMode.OS), FakeHost.mac());
        assertThatThrownBy(() -> wrap(s, "srv", link)).isInstanceOf(StdioSandboxException.class);
    }

    @Test
    void statusCarriesNoPathsOrSecrets(@TempDir Path dir) {
        StdioSandboxProperties p = props(StdioSandboxMode.OS);
        p.setSandboxNetworkAllow(List.of("github"));
        StdioSandboxStatus st = service(p, FakeHost.mac()).status();
        assertThat(st.networkAllowCount()).isEqualTo(1);
        assertThat(st.networkAllowAll()).isFalse();
        // any authenticated user can read the status: server names must not leak into it
        assertThat(st.toString())
                .doesNotContain("github")
                .doesNotContain("/Users")
                .doesNotContain(dir.toString());
        p.setSandboxNetworkAllow(List.of("*"));
        assertThat(service(p, FakeHost.mac()).status().networkAllowAll()).isTrue();
    }

    @Test
    void propertiesBindFromKebabCaseKeys() {
        var source =
                new org.springframework.boot.context.properties.source.MapConfigurationPropertySource(
                        Map.of(
                                "app.mcp.stdio.sandbox", "container",
                                "app.mcp.stdio.sandbox-required", "false",
                                "app.mcp.stdio.sandbox-network-allow", "github,slack",
                                "app.mcp.stdio.preflight-fail-fast", "false"));
        StdioSandboxProperties p =
                new org.springframework.boot.context.properties.bind.Binder(source)
                        .bind("app.mcp.stdio", StdioSandboxProperties.class)
                        .get();
        assertThat(p.getSandbox()).isEqualTo(StdioSandboxMode.CONTAINER);
        assertThat(p.isSandboxRequired()).isFalse();
        assertThat(p.networkAllowedFor("SLACK")).isTrue();
        assertThat(p.networkAllowedFor("other")).isFalse();
        assertThat(new StdioSandboxProperties().getSandbox()).isEqualTo(StdioSandboxMode.NONE);
        assertThat(new StdioSandboxProperties().isSandboxRequired()).isTrue();
    }
}
