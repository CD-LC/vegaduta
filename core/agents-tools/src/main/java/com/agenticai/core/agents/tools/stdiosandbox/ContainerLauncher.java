package com.agenticai.core.agents.tools.stdiosandbox;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Builds the {@code docker}/{@code podman run} argv for container mode. Pure.
 *
 * <p>Tenant env values are NEVER placed on the command line (visible to every local user in
 * {@code ps}): they are forwarded with the value-less {@code -e NAME} form, which makes the
 * container CLI read the value from its own environment - the environment {@code
 * EnvIsolatedStdioClientTransport} hands it, which is exactly the approved env. Names are checked
 * against {@code [A-Za-z_][A-Za-z0-9_]*} first, so a name cannot smuggle a second flag or an
 * {@code =value}.
 */
final class ContainerLauncher {

    /** Container-side data mount; all caches, HOME and scratch live under it. */
    static final String DATA_MOUNT = "/data";

    /** Names the platform sets itself (host paths, meaningless inside a container). */
    static final Set<String> PLATFORM_OWNED =
            Set.of(
                    "PATH",
                    "HOME",
                    "TMPDIR",
                    "TMP",
                    "TEMP",
                    "NPM_CONFIG_CACHE",
                    "NPM_CONFIG_USERCONFIG",
                    "NPM_CONFIG_GLOBALCONFIG",
                    "NPM_CONFIG_PREFIX",
                    "UV_CACHE_DIR",
                    "UV_TOOL_DIR",
                    "UV_TOOL_BIN_DIR",
                    "UV_PYTHON_INSTALL_DIR",
                    "UV_PYTHON_BIN_DIR",
                    "PIP_CACHE_DIR",
                    "XDG_CACHE_HOME",
                    "XDG_CONFIG_HOME",
                    "XDG_DATA_HOME",
                    "XDG_STATE_HOME",
                    "XDG_RUNTIME_DIR");

    /**
     * Name prefixes (upper-case) that steer the {@code docker}/{@code podman} CLI itself (daemon
     * address, TLS, config and plugin directories, storage and helper-binary paths). The tenant
     * env is the environment of that CLI process (so {@code -e NAME} can read each value), and the
     * command guard does not block these names, so a registration could otherwise point the CLI
     * at another daemon or make it run a helper of its choosing on the host. Refused outright.
     */
    static final List<String> CLI_CONTROL_PREFIXES =
            List.of("DOCKER", "CONTAINER", "PODMAN", "BUILDAH", "REGISTRY_AUTH", "STORAGE_", "SSH_");

    private ContainerLauncher() {}

    static boolean controlsContainerCli(String name) {
        String upper = name.toUpperCase(Locale.ROOT);
        for (String prefix : CLI_CONTROL_PREFIXES) {
            if (upper.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    /** The fixed in-container environment (literal values, none secret). */
    static Map<String, String> containerEnv() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("HOME", DATA_MOUNT + "/home");
        m.put("TMPDIR", "/tmp");
        m.put("npm_config_cache", DATA_MOUNT + "/npm");
        m.put("UV_CACHE_DIR", DATA_MOUNT + "/uv-cache");
        m.put("UV_TOOL_DIR", DATA_MOUNT + "/uv-tools");
        m.put("UV_TOOL_BIN_DIR", DATA_MOUNT + "/uv-bin");
        m.put("XDG_CACHE_HOME", DATA_MOUNT + "/xdg-cache");
        m.put("XDG_CONFIG_HOME", DATA_MOUNT + "/xdg-config");
        m.put("XDG_DATA_HOME", DATA_MOUNT + "/xdg-data");
        m.put("XDG_STATE_HOME", DATA_MOUNT + "/xdg-state");
        return m;
    }

    /** Image for a launcher; null when the launcher has no configured image. */
    static String imageFor(String command, StdioSandboxProperties props) {
        if (command == null) {
            return null;
        }
        String c = command.toLowerCase(Locale.ROOT);
        for (String suffix : List.of(".cmd", ".exe", ".bat")) {
            if (c.endsWith(suffix)) {
                c = c.substring(0, c.length() - suffix.length());
            }
        }
        return switch (c) {
            case "node", "npx" -> props.getSandboxContainerImageNode();
            case "uv", "uvx", "python", "python3" -> props.getSandboxContainerImagePython();
            default -> null;
        };
    }

    /**
     * @param runtime absolute path to docker/podman
     * @param user {@code uid:gid} or blank for the image default
     */
    static List<String> run(
            String runtime, SandboxRequest req, StdioSandboxProperties props, String user) {
        return run(runtime, req, props, user, imageFor(req.command(), props));
    }

    /** Test seam: an explicit image (the launchers' real images are too large to pull in tests). */
    static List<String> run(
            String runtime,
            SandboxRequest req,
            StdioSandboxProperties props,
            String user,
            String image) {
        if (image == null || image.isBlank()) {
            throw new StdioSandboxException(
                    "No container image is configured for the launcher '"
                            + safeName(req.command())
                            + "'.");
        }
        SandboxText.requirePlain(image, "container image");
        if (image.startsWith("-") || image.chars().anyMatch(Character::isWhitespace)) {
            throw new StdioSandboxException("The configured container image name is not valid.");
        }
        String memory = props.getSandboxContainerMemory();
        if (memory == null || !memory.matches("[0-9]+[bkmgBKMG]?")) {
            throw new StdioSandboxException("The configured container memory limit is not valid.");
        }
        String data = SandboxText.requireMountPath(req.dataDir(), "data directory");

        List<String> a = new ArrayList<>();
        a.add(runtime);
        a.add("run");
        a.add("--rm");
        a.add("-i");
        a.add("--init");
        a.add("--read-only");
        a.add("--security-opt");
        a.add("no-new-privileges");
        a.add("--cap-drop");
        a.add("ALL");
        a.add("--memory");
        a.add(memory);
        a.add("--memory-swap");
        a.add(memory);
        a.add("--pids-limit");
        a.add(Integer.toString(Math.max(16, props.getSandboxContainerPids())));
        a.add("--network");
        a.add(req.networkAccess() ? "bridge" : "none");
        a.add("--tmpfs");
        a.add("/tmp:rw,nosuid,size=256m");
        a.add("--mount");
        a.add("type=bind,source=" + data + ",target=" + DATA_MOUNT);
        a.add("--workdir");
        a.add(DATA_MOUNT);
        if (user != null && !user.isBlank()) {
            if (!user.matches("[0-9]+:[0-9]+")) {
                throw new StdioSandboxException("The container user must be uid:gid.");
            }
            a.add("--user");
            a.add(user);
        }
        containerEnv().forEach((k, v) -> {
            a.add("-e");
            a.add(k + "=" + v);
        });
        for (String name : new java.util.TreeSet<>(req.env().keySet())) {
            if (PLATFORM_OWNED.contains(name.toUpperCase(Locale.ROOT))) {
                continue;
            }
            if (!SandboxText.validEnvName(name)) {
                throw new StdioSandboxException(
                        "An environment variable name is not valid for a container: "
                                + safeName(name));
            }
            if (controlsContainerCli(name)) {
                throw new StdioSandboxException(
                        "An environment variable name is reserved for the container runtime: "
                                + safeName(name));
            }
            a.add("-e");
            a.add(name);
        }
        a.add("--");
        a.add(image);
        a.add(req.command());
        a.addAll(req.args());
        return List.copyOf(a);
    }

    private static String safeName(String s) {
        if (s == null) {
            return "(none)";
        }
        String t = s.length() > 40 ? s.substring(0, 40) : s;
        return t.replaceAll("[^A-Za-z0-9_.-]", "?");
    }
}
