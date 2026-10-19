package com.agenticai.core.agents.tools.stdiosandbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Argument construction for bubblewrap, unshare and the container runtimes. */
class LaunchersTest {

    private static final Path DATA = Path.of("/home/test/.vegaduta/c/sandbox");

    private static SandboxRequest req(boolean network, Map<String, String> env, String... args) {
        return new SandboxRequest("srv", "npx", List.of(args), env, DATA, network);
    }

    // ---- bubblewrap -------------------------------------------------------------------------

    @Test
    void bubblewrapGoldenArgvWithoutNetwork() {
        List<String> argv =
                LinuxLaunchers.bubblewrap(
                        "/usr/bin/bwrap",
                        req(false, Map.of(), "-y", "@scope/pkg"),
                        Path.of("/home/test"),
                        List.of(Path.of("/home/test/.nvm/versions/node/v22")),
                        true);
        assertThat(argv)
                .containsExactly(
                        "/usr/bin/bwrap",
                        "--die-with-parent",
                        "--new-session",
                        "--unshare-user",
                        "--unshare-pid",
                        "--unshare-ipc",
                        "--unshare-uts",
                        "--unshare-cgroup-try",
                        "--unshare-net",
                        "--cap-drop",
                        "ALL",
                        "--ro-bind",
                        "/",
                        "/",
                        "--dev",
                        "/dev",
                        "--proc",
                        "/proc",
                        "--tmpfs",
                        "/tmp",
                        "--tmpfs",
                        "/run",
                        "--tmpfs",
                        "/home/test",
                        "--ro-bind-try",
                        "/home/test/.nvm/versions/node/v22",
                        "/home/test/.nvm/versions/node/v22",
                        "--bind",
                        DATA.toString(),
                        DATA.toString(),
                        "--chdir",
                        DATA.toString(),
                        "--",
                        "npx",
                        "-y",
                        "@scope/pkg");
    }

    @Test
    void bubblewrapSharesNetworkOnlyWhenAskedAndKeepsResolverReachable() {
        List<String> off = LinuxLaunchers.bubblewrap("/b", req(false, Map.of()), Path.of("/home/t"), List.of(), true);
        List<String> on = LinuxLaunchers.bubblewrap("/b", req(true, Map.of()), Path.of("/home/t"), List.of(), true);
        assertThat(off).contains("--unshare-net").doesNotContain("--share-net");
        assertThat(on).contains("--share-net").doesNotContain("--unshare-net");
        assertThat(on).containsSequence("--ro-bind-try", "/run/systemd/resolve", "/run/systemd/resolve");
        assertThat(off).doesNotContain("/run/systemd/resolve");
    }

    @Test
    void hostileArgsStayOneElementEach() {
        String evil = "x; rm -rf / `id` $(id) \n --bind / /";
        List<String> argv = LinuxLaunchers.bubblewrap("/b", req(false, Map.of(), evil), null, List.of(), true);
        assertThat(argv.subList(argv.indexOf("--") + 1, argv.size())).containsExactly("npx", evil);
        // nothing before the "--" separator comes from the request's args
        assertThat(argv.subList(0, argv.indexOf("--"))).noneMatch(s -> s.contains("rm -rf"));
    }

    @Test
    void bubblewrapRefusesAControlCharacterInTheDataDir() {
        SandboxRequest bad = new SandboxRequest("s", "npx", List.of(), Map.of(), Path.of("/d/a\nb"), false);
        assertThatThrownBy(() -> LinuxLaunchers.bubblewrap("/b", bad, null, List.of(), true))
                .isInstanceOf(StdioSandboxException.class);
    }

    @Test
    void unshareGolden() {
        assertThat(LinuxLaunchers.unshare("/usr/bin/unshare", req(false, Map.of(), "a")))
                .containsExactly(
                        "/usr/bin/unshare", "--user", "--map-root-user", "--pid", "--fork", "--kill-child",
                        "--ipc", "--uts", "--net", "--", "npx", "a");
        assertThat(LinuxLaunchers.unshare("/usr/bin/unshare", req(true, Map.of())))
                .doesNotContain("--net");
    }

    // ---- containers -------------------------------------------------------------------------

    private static StdioSandboxProperties props() {
        return new StdioSandboxProperties();
    }

    @Test
    void containerGoldenArgvHardening() {
        List<String> argv =
                ContainerLauncher.run(
                        "/usr/local/bin/docker",
                        req(false, Map.of("GITHUB_TOKEN", "ghp_secret"), "-y", "pkg"),
                        props(),
                        "501:20");
        assertThat(argv.subList(0, 3)).containsExactly("/usr/local/bin/docker", "run", "--rm");
        assertThat(argv)
                .containsSequence("--read-only")
                .containsSequence("--security-opt", "no-new-privileges")
                .containsSequence("--cap-drop", "ALL")
                .containsSequence("--memory", "512m")
                .containsSequence("--memory-swap", "512m")
                .containsSequence("--pids-limit", "256")
                .containsSequence("--network", "none")
                .containsSequence("--user", "501:20")
                .containsSequence("--mount", "type=bind,source=" + DATA + ",target=/data")
                .containsSequence("--", "node:22-slim", "npx", "-y", "pkg");
        // only ONE bind mount: the data dir
        assertThat(argv.stream().filter(s -> s.startsWith("type=bind")).count()).isEqualTo(1);
    }

    @Test
    void tenantEnvValuesNeverAppearOnTheCommandLine() {
        List<String> argv =
                ContainerLauncher.run(
                        "docker", req(false, Map.of("GITHUB_TOKEN", "ghp_secret", "A_B", "v")), props(), null);
        assertThat(String.join(" ", argv)).doesNotContain("ghp_secret");
        assertThat(argv).containsSequence("-e", "GITHUB_TOKEN").containsSequence("-e", "A_B");
        assertThat(argv).doesNotContain("--user");
    }

    @Test
    void platformOwnedAndHostPathEnvIsNotForwardedAndContainerPathsAreFixed() {
        List<String> argv =
                ContainerLauncher.run(
                        "docker",
                        req(
                                false,
                                Map.of(
                                        "HOME", "/Users/x",
                                        "PATH", "/Users/x/bin",
                                        "npm_config_cache", "/Users/x/c",
                                        "UV_CACHE_DIR", "/Users/x/u")),
                        props(),
                        null);
        assertThat(argv).doesNotContain("HOME", "PATH", "npm_config_cache", "UV_CACHE_DIR");
        assertThat(argv).contains("HOME=/data/home", "npm_config_cache=/data/npm", "UV_CACHE_DIR=/data/uv-cache");
    }

    @Test
    void networkIsBridgedOnlyWhenAllowed() {
        assertThat(ContainerLauncher.run("docker", req(true, Map.of()), props(), null))
                .containsSequence("--network", "bridge");
    }

    @Test
    void envNamesThatCouldSmuggleAFlagOrValueAreRefused() {
        for (String bad : List.of("A=B", "--privileged", "A B", "a,b", "1X", "", "X\nY")) {
            assertThatThrownBy(
                            () -> ContainerLauncher.run("docker", req(false, Map.of(bad, "v")), props(), null))
                    .as("env name %s", bad)
                    .isInstanceOf(StdioSandboxException.class);
        }
    }

    @Test
    void mountPathsThatBreakTheMountSyntaxAreRefused() {
        for (String bad : List.of("/d/a,readonly=false", "/d/a:b", "/d/a\"b", "/d/a\\b", "/d/a\nb")) {
            SandboxRequest r = new SandboxRequest("s", "npx", List.of(), Map.of(), Path.of(bad), false);
            assertThatThrownBy(() -> ContainerLauncher.run("docker", r, props(), null))
                    .as(bad)
                    .isInstanceOf(StdioSandboxException.class);
        }
    }

    @Test
    void imageSelectionByLauncherAndUnknownLauncherIsRefused() {
        StdioSandboxProperties p = props();
        assertThat(ContainerLauncher.imageFor("npx", p)).isEqualTo(p.getSandboxContainerImageNode());
        assertThat(ContainerLauncher.imageFor("npx.cmd", p)).isEqualTo(p.getSandboxContainerImageNode());
        assertThat(ContainerLauncher.imageFor("uvx", p)).isEqualTo(p.getSandboxContainerImagePython());
        assertThat(ContainerLauncher.imageFor("python3", p)).isEqualTo(p.getSandboxContainerImagePython());
        assertThat(ContainerLauncher.imageFor("bash", p)).isNull();
        SandboxRequest r = new SandboxRequest("s", "bash", List.of(), Map.of(), DATA, false);
        assertThatThrownBy(() -> ContainerLauncher.run("docker", r, p, null))
                .isInstanceOf(StdioSandboxException.class);
    }

    @Test
    void badImageMemoryAndUserSettingsAreRefused() {
        StdioSandboxProperties p = props();
        p.setSandboxContainerImageNode("--privileged");
        assertThatThrownBy(() -> ContainerLauncher.run("docker", req(false, Map.of()), p, null))
                .isInstanceOf(StdioSandboxException.class);
        StdioSandboxProperties m = props();
        m.setSandboxContainerMemory("1g --privileged");
        assertThatThrownBy(() -> ContainerLauncher.run("docker", req(false, Map.of()), m, null))
                .isInstanceOf(StdioSandboxException.class);
        assertThatThrownBy(() -> ContainerLauncher.run("docker", req(false, Map.of()), props(), "0:0 --privileged"))
                .isInstanceOf(StdioSandboxException.class);
    }

    @Test
    void envNamesThatSteerTheContainerCliItselfAreRefused() {
        // The tenant env is the environment of the docker/podman CLI process: DOCKER_HOST would
        // aim it at another daemon, CONTAINERS_CONF at an attacker-chosen helper binary.
        for (String bad : List.of("DOCKER_HOST", "docker_host", "DOCKER_CONFIG", "DOCKER_CONTEXT",
                "CONTAINER_HOST", "CONTAINERS_CONF", "PODMAN_CONNECTIONS_CONF", "REGISTRY_AUTH_FILE",
                "SSH_AUTH_SOCK")) {
            assertThatThrownBy(
                            () -> ContainerLauncher.run("docker", req(false, Map.of(bad, "tcp://evil:2375")), props(), null))
                    .as("env name %s", bad)
                    .isInstanceOf(StdioSandboxException.class)
                    .hasMessageContaining("reserved")
                    .hasMessageNotContaining("evil");
        }
        // ordinary names that merely resemble them are untouched
        assertThat(ContainerLauncher.run("docker", req(false, Map.of("GITHUB_TOKEN", "t", "MY_DOCKERISH", "1")), props(), null))
                .containsSequence("-e", "GITHUB_TOKEN");
    }

    @Test
    void bubblewrapHidesThePrivateRootOutsideHomeButNotOneAlreadyMasked() {
        Path data = Path.of("/var/lib/vd/cache/npm/7/sandbox");
        SandboxRequest r = new SandboxRequest("srv", "npx", List.of(), Map.of(), data, false);
        List<String> outside =
                LinuxLaunchers.bubblewrap("/b", r, Path.of("/home/t"), List.of(), true, Path.of("/var/lib/vd/cache"));
        assertThat(outside).containsSequence("--tmpfs", "/var/lib/vd/cache", "--bind", data.toString());

        SandboxRequest inHome = new SandboxRequest("srv", "npx", List.of(), Map.of(), DATA, false);
        List<String> masked =
                LinuxLaunchers.bubblewrap("/b", inHome, Path.of("/home/test"), List.of(), true, Path.of("/home/test/.vegaduta"));
        assertThat(masked).doesNotContain("/home/test/.vegaduta");
        // no private root: identical to the five-argument form
        assertThat(LinuxLaunchers.bubblewrap("/b", r, Path.of("/home/t"), List.of(), true, null))
                .isEqualTo(LinuxLaunchers.bubblewrap("/b", r, Path.of("/home/t"), List.of(), true));
    }

    @Test
    void pidsLimitHasAFloor() {
        StdioSandboxProperties p = props();
        p.setSandboxContainerPids(1);
        assertThat(ContainerLauncher.run("docker", req(false, Map.of()), p, null))
                .containsSequence("--pids-limit", "16");
    }

    @Test
    void bubblewrapHidesConfiguredDenyReadPathsBeforeBindingTheDataDirBack(@org.junit.jupiter.api.io.TempDir Path tmp)
            throws Exception {
        Path dir = java.nio.file.Files.createDirectories(tmp.resolve("compose"));
        Path file = java.nio.file.Files.writeString(tmp.resolve("secret.env"), "K=v");

        List<String> argv =
                LinuxLaunchers.bubblewrap(
                        "/usr/bin/bwrap",
                        req(false, Map.of(), "-y"),
                        Path.of("/home/test"),
                        List.of(),
                        true,
                        null,
                        List.of(dir, file));

        int tmpfs = argv.indexOf(dir.toString());
        assertThat(argv.get(tmpfs - 1)).isEqualTo("--tmpfs");
        int devnull = argv.indexOf(file.toString());
        assertThat(argv.subList(devnull - 2, devnull + 1)).containsExactly("--ro-bind", "/dev/null", file.toString());
        assertThat(tmpfs).isLessThan(argv.indexOf("--bind"));
        assertThat(devnull).isLessThan(argv.indexOf("--bind"));
    }
}
