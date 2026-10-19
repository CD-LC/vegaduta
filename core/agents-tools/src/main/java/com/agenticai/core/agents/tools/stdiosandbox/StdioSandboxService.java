package com.agenticai.core.agents.tools.stdiosandbox;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Confines the dynamic STDIO MCP server children core spawns itself ({@code
 * app.mcp.stdio.sandbox}). It wraps an ALREADY guard-approved command in an OS sandbox or a
 * container; it never replaces {@code McpStdioCommandGuard} or env isolation, which still run
 * first and still decide what may start at all.
 *
 * <p>Behaviour matrix:
 *
 * <ul>
 *   <li>{@code none} (default) - {@link #wrap} returns empty; nothing changes.
 *   <li>{@code os}/{@code container}, mechanism available - the child is wrapped.
 *   <li>mechanism unavailable and {@code sandbox-required=true} (default) - the spawn is REFUSED
 *       with a {@link StdioSandboxException} (and core fails to start unless {@code
 *       app.mcp.stdio.preflight-fail-fast=false}).
 *   <li>mechanism unavailable and {@code sandbox-required=false} - unsandboxed, with a WARN on
 *       every spawn naming the server.
 * </ul>
 *
 * Registered in {@link StdioSandboxRuntime} so the plain-object {@code McpStdioParamsBuilder} (not
 * a bean, constructed inside {@code DynamicMcpConnectionManager}, whose constructor signature is
 * frozen by the architecture baselines) can reach it.
 */
@Component
public class StdioSandboxService {

    private static final Logger log = LoggerFactory.getLogger(StdioSandboxService.class);
    private static final String SEATBELT = "/usr/bin/sandbox-exec";
    private static final Set<PosixFilePermission> OWNER_ONLY =
            PosixFilePermissions.fromString("rwx------");
    private static final int PROBE_TIMEOUT_SECONDS = 15;

    /**
     * An UNAVAILABLE probe result is re-checked after this long, so a container daemon (or any
     * mechanism) that came up after core started is picked up without a restart. An available
     * result is kept.
     */
    private static final long REPROBE_UNAVAILABLE_NANOS = java.util.concurrent.TimeUnit.SECONDS.toNanos(30);

    /** Which mechanism resolved on this host (immutable once computed). */
    private record Resolved(
            String kind, String path, boolean available, boolean degraded, String detail) {}

    private final StdioSandboxProperties props;
    private final HostProbe host;
    private final boolean failFast;
    private final java.util.function.LongSupplier nanoClock;
    private volatile Resolved resolved;
    private volatile long resolvedAtNanos;

    @org.springframework.beans.factory.annotation.Autowired
    public StdioSandboxService(
            StdioSandboxProperties props,
            @Value("${app.mcp.stdio.preflight-fail-fast:true}") boolean failFast) {
        this(props, new HostProbe.Real(), failFast);
    }

    StdioSandboxService(StdioSandboxProperties props, HostProbe host, boolean failFast) {
        this(props, host, failFast, System::nanoTime);
    }

    StdioSandboxService(
            StdioSandboxProperties props,
            HostProbe host,
            boolean failFast,
            java.util.function.LongSupplier nanoClock) {
        this.props = props;
        this.host = host;
        this.failFast = failFast;
        this.nanoClock = nanoClock;
    }

    /**
     * Startup preflight: reports (and, for a required-but-missing mechanism, fails) before the
     * first agent reaches for a tool.
     */
    @PostConstruct
    void start() {
        preflight();
        StdioSandboxRuntime.install(this);
    }

    void preflight() {
        if (props.getSandbox() == StdioSandboxMode.NONE) {
            return;
        }
        StdioSandboxStatus s = status();
        if (s.available()) {
            if (s.degraded()) {
                log.warn("MCP STDIO sandbox: {}", s.detail());
            } else {
                log.info("MCP STDIO sandbox: {}", s.detail());
            }
            return;
        }
        String msg =
                "app.mcp.stdio.sandbox="
                        + s.mode()
                        + " but the sandbox is unavailable: "
                        + s.detail();
        if (props.isSandboxRequired()) {
            if (failFast) {
                log.error(msg);
                throw new IllegalStateException(
                        msg
                                + " Set app.mcp.stdio.sandbox=none, fix the host, or set"
                                + " app.mcp.stdio.sandbox-required=false to run STDIO servers"
                                + " unsandboxed with a warning.");
            }
            log.error("{} - every STDIO MCP spawn will be refused.", msg);
        } else {
            log.warn(
                    "{} - app.mcp.stdio.sandbox-required=false: STDIO MCP servers will run"
                            + " UNSANDBOXED.",
                    msg);
        }
    }

    @PreDestroy
    void uninstall() {
        StdioSandboxRuntime.uninstall(this);
    }

    public boolean enabled() {
        return props.getSandbox() != StdioSandboxMode.NONE;
    }

    /** Pure report for the status/doctor surface. Probes the host once, then caches. */
    public StdioSandboxStatus status() {
        StdioSandboxMode mode = props.getSandbox();
        List<String> network = props.getSandboxNetworkAllow();
        boolean networkAll = network.stream().anyMatch(e -> e != null && e.strip().equals("*"));
        int networkCount = (int) network.stream().filter(e -> e != null && !e.isBlank()).count();
        if (mode == StdioSandboxMode.NONE) {
            return new StdioSandboxStatus(
                    "none",
                    "none",
                    true,
                    false,
                    false,
                    props.isSandboxRequired(),
                    networkAll,
                    networkCount,
                    "Sandboxing is off (app.mcp.stdio.sandbox=none): STDIO MCP servers run as plain"
                            + " subprocesses of core.");
        }
        Resolved r = resolve();
        return new StdioSandboxStatus(
                mode.name().toLowerCase(Locale.ROOT),
                r.available() ? r.kind() : "none",
                r.available(),
                r.degraded(),
                r.available() || props.isSandboxRequired(),
                props.isSandboxRequired(),
                networkAll,
                networkCount,
                r.detail());
    }

    /**
     * Wraps an approved command, or returns empty when sandboxing is off / unavailable-but-optional.
     *
     * @param dataDir the one writable directory (created if needed)
     * @throws StdioSandboxException when the spawn must be refused
     */
    public Optional<SandboxLaunch> wrap(
            String serverName,
            String command,
            List<String> args,
            Map<String, String> env,
            Path dataDir) {
        return wrap(serverName, command, args, env, dataDir, null);
    }

    /**
     * As {@link #wrap(String, String, List, Map, Path)}, additionally hiding {@code privateRoot}
     * (the shared per-registration cache base that holds every OTHER registration's data
     * directory) from the child; only {@code dataDir} inside it stays visible. May be null.
     */
    public Optional<SandboxLaunch> wrap(
            String serverName,
            String command,
            List<String> args,
            Map<String, String> env,
            Path dataDir,
            Path privateRoot) {
        if (props.getSandbox() == StdioSandboxMode.NONE) {
            return Optional.empty();
        }
        Resolved r = resolve();
        if (!r.available()) {
            if (props.isSandboxRequired()) {
                throw new StdioSandboxException(
                        "STDIO MCP servers are refused: the sandbox (app.mcp.stdio.sandbox="
                                + props.getSandbox().name().toLowerCase(Locale.ROOT)
                                + ") is unavailable on this host. "
                                + r.detail());
            }
            log.warn(
                    "MCP STDIO server '{}' is starting UNSANDBOXED: the sandbox is unavailable ({})"
                            + " and app.mcp.stdio.sandbox-required=false.",
                    safeForLog(serverName),
                    r.detail());
            return Optional.empty();
        }
        Path real = prepareDataDir(dataDir);
        boolean network = props.networkAllowedFor(serverName);
        SandboxRequest req = new SandboxRequest(serverName, command, args, env, real, network);
        SandboxLaunch launch = build(r, req, realOrNull(privateRoot));
        if (launch.degraded()) {
            log.warn(
                    "MCP STDIO server '{}' runs in DEGRADED sandbox mode ({}): namespaces only, the"
                            + " filesystem is NOT confined. Install bubblewrap (bwrap).",
                    safeForLog(serverName),
                    launch.mechanism());
        }
        return Optional.of(launch);
    }

    /** The real path (profiles match resolved paths), or null when absent / unresolvable. */
    private static Path realOrNull(Path p) {
        if (p == null) {
            return null;
        }
        try {
            return p.toAbsolutePath().normalize().toRealPath();
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    // ------------------------------------------------------------------ //
    // Building the wrapped launch
    // ------------------------------------------------------------------ //

    private SandboxLaunch build(Resolved r, SandboxRequest req, Path privateRoot) {
        return switch (r.kind()) {
            case "seatbelt" -> {
                String profile =
                        SeatbeltProfile.render(
                                req.dataDir(),
                                host.home(),
                                denyReadPaths(privateRoot),
                                req.networkAccess(),
                                props.isSandboxAllowLoopback());
                List<String> argv = new ArrayList<>();
                argv.add("-p");
                argv.add(profile);
                argv.add(req.command());
                argv.addAll(req.args());
                yield new SandboxLaunch(r.path(), argv, confinedEnv(req), "seatbelt", false);
            }
            case "bubblewrap" -> {
                List<String> argv =
                        LinuxLaunchers.bubblewrap(
                                r.path(),
                                req,
                                host.home(),
                                readOnlyUnderHome(req.command()),
                                true,
                                privateRoot,
                                existingDenyReadPaths());
                yield splitLaunch(argv, confinedEnv(req), "bubblewrap", false);
            }
            case "unshare" ->
                    splitLaunch(
                            LinuxLaunchers.unshare(r.path(), req),
                            confinedEnv(req),
                            "unshare",
                            true);
            case "docker", "podman" -> {
                List<String> argv =
                        ContainerLauncher.run(r.path(), req, props, containerUser());
                yield splitLaunch(argv, containerCliEnv(req), r.kind(), false);
            }
            default -> throw new StdioSandboxException("Unknown sandbox mechanism.");
        };
    }

    private static SandboxLaunch splitLaunch(
            List<String> argv, Map<String, String> env, String mechanism, boolean degraded) {
        return new SandboxLaunch(argv.get(0), argv.subList(1, argv.size()), env, mechanism, degraded);
    }

    private String containerUser() {
        String configured = props.getSandboxContainerUser();
        return configured.isBlank() ? host.uidGid() : configured;
    }

    private List<Path> denyReadPaths(Path privateRoot) {
        List<Path> out = new ArrayList<>();
        if (privateRoot != null && privateRoot.isAbsolute()) {
            out.add(privateRoot);
        }
        for (String s : props.getSandboxExtraDenyRead()) {
            if (s != null && !s.isBlank()) {
                out.add(configuredPath(s));
            }
        }
        return out;
    }

    /** Configured deny-read paths that exist (bubblewrap cannot mount over a missing path). */
    private List<Path> existingDenyReadPaths() {
        List<Path> out = new ArrayList<>();
        for (Path p : denyReadPaths(null)) {
            if (java.nio.file.Files.exists(p)) {
                out.add(p);
            }
        }
        return out;
    }

    private static Path configuredPath(String s) {
        try {
            return Path.of(s.strip());
        } catch (java.nio.file.InvalidPathException e) {
            throw new StdioSandboxException("A configured sandbox path is not a valid path.");
        }
    }

    /** Toolchain directories under the (masked) home that must stay visible read-only. */
    private List<Path> readOnlyUnderHome(String command) {
        Path home = host.home();
        List<Path> out = new ArrayList<>();
        if (home == null) {
            return out;
        }
        for (Path dir : host.pathDirs()) {
            if (dir.isAbsolute() && dir.startsWith(home)) {
                out.add(dir);
            }
        }
        host.which(command)
                .ifPresent(
                        found -> {
                            try {
                                Path real = Path.of(found).toRealPath();
                                Path prefix = real.getParent() == null ? null : real.getParent().getParent();
                                if (prefix != null && prefix.startsWith(home) && !prefix.equals(home)) {
                                    out.add(prefix);
                                }
                            } catch (IOException | RuntimeException ignored) {
                                // the PATH dir above is still bound
                            }
                        });
        for (String s : props.getSandboxExtraReadOnly()) {
            if (s != null && !s.isBlank()) {
                out.add(configuredPath(s));
            }
        }
        return out;
    }

    /**
     * The os-mode child env: the approved env with every platform-owned name (HOME, TMPDIR, the
     * npm/uv/XDG caches) removed in any case and re-pointed inside the data directory, so neither
     * the platform default nor a tenant-supplied value can aim a write elsewhere.
     */
    static Map<String, String> confinedEnv(SandboxRequest req) {
        Map<String, String> env = new LinkedHashMap<>();
        req.env()
                .forEach(
                        (k, v) -> {
                            if (k != null
                                    && v != null
                                    && !ContainerLauncher.PLATFORM_OWNED.contains(
                                            k.toUpperCase(Locale.ROOT))) {
                                env.put(k, v);
                            }
                        });
        String d = req.dataDir().toString();
        env.put("HOME", d + "/home");
        env.put("TMPDIR", d + "/tmp");
        env.put("npm_config_cache", d + "/npm");
        env.put("UV_CACHE_DIR", d + "/uv-cache");
        env.put("UV_TOOL_DIR", d + "/uv-tools");
        env.put("UV_TOOL_BIN_DIR", d + "/uv-bin");
        env.put("XDG_CACHE_HOME", d + "/xdg-cache");
        env.put("XDG_CONFIG_HOME", d + "/xdg-config");
        env.put("XDG_DATA_HOME", d + "/xdg-data");
        env.put("XDG_STATE_HOME", d + "/xdg-state");
        return env;
    }

    /**
     * The environment of the {@code docker}/{@code podman} CLI process itself: the approved tenant
     * env (so {@code -e NAME} can read each value) minus platform-owned names. PATH/HOME defaults
     * for the CLI are added by the SDK's {@code ServerParameters}.
     */
    static Map<String, String> containerCliEnv(SandboxRequest req) {
        Map<String, String> env = new LinkedHashMap<>();
        req.env()
                .forEach(
                        (k, v) -> {
                            if (k != null
                                    && v != null
                                    && !ContainerLauncher.PLATFORM_OWNED.contains(
                                            k.toUpperCase(Locale.ROOT))) {
                                env.put(k, v);
                            }
                        });
        return env;
    }

    /** Creates the data directory tree owner-only and returns its real path. */
    private static Path prepareDataDir(Path dataDir) {
        try {
            if (dataDir == null) {
                throw new StdioSandboxException("The sandbox has no data directory.");
            }
            Path dir = dataDir.toAbsolutePath().normalize();
            if (Files.isSymbolicLink(dir)) {
                throw new StdioSandboxException("The sandbox data directory is a symbolic link.");
            }
            boolean posix =
                    java.nio.file.FileSystems.getDefault()
                            .supportedFileAttributeViews()
                            .contains("posix");
            for (String sub :
                    List.of(
                            "",
                            "home",
                            "tmp",
                            "npm",
                            "uv-cache",
                            "uv-tools",
                            "uv-bin",
                            "xdg-cache",
                            "xdg-config",
                            "xdg-data",
                            "xdg-state")) {
                Path p = sub.isEmpty() ? dir : dir.resolve(sub);
                if (posix) {
                    Files.createDirectories(
                            p, PosixFilePermissions.asFileAttribute(OWNER_ONLY));
                } else {
                    Files.createDirectories(p);
                }
            }
            return dir.toRealPath();
        } catch (IOException e) {
            throw new StdioSandboxException(
                    "The sandbox data directory could not be prepared: " + e.getClass().getSimpleName());
        }
    }

    // ------------------------------------------------------------------ //
    // Mechanism resolution
    // ------------------------------------------------------------------ //

    private Resolved resolve() {
        Resolved r = resolved;
        if (r == null || (!r.available() && stale())) {
            synchronized (this) {
                r = resolved;
                if (r == null || (!r.available() && stale())) {
                    r = props.getSandbox() == StdioSandboxMode.CONTAINER ? resolveContainer() : resolveOs();
                    resolvedAtNanos = nanoClock.getAsLong();
                    resolved = r;
                }
            }
        }
        return r;
    }

    private boolean stale() {
        return nanoClock.getAsLong() - resolvedAtNanos >= REPROBE_UNAVAILABLE_NANOS;
    }

    private Resolved resolveOs() {
        String os = host.os();
        if (os.equals("mac")) {
            if (!host.executable(SEATBELT)) {
                return unavailable("os", "sandbox-exec is not present on this macOS.");
            }
            if (!host.runsOk(
                    List.of(SEATBELT, "-p", "(version 1)(allow default)", "/usr/bin/true"),
                    PROBE_TIMEOUT_SECONDS)) {
                return unavailable(
                        "os",
                        "sandbox-exec cannot apply a profile here (core may itself already run inside"
                                + " an OS sandbox, which does not allow a nested one).");
            }
            return new Resolved(
                    "seatbelt",
                    SEATBELT,
                    true,
                    false,
                    "macOS sandbox-exec (deny-default profile; read-only filesystem except the server"
                            + " data directory; credentials masked; network "
                            + networkSummary()
                            + ").");
        }
        if (os.equals("linux")) {
            Optional<String> bwrap = host.which("bwrap");
            String truePath = host.executable("/usr/bin/true") ? "/usr/bin/true" : "/bin/true";
            if (bwrap.isPresent()
                    && host.runsOk(
                            List.of(
                                    bwrap.get(),
                                    "--ro-bind",
                                    "/",
                                    "/",
                                    "--unshare-user",
                                    "--dev",
                                    "/dev",
                                    truePath),
                            PROBE_TIMEOUT_SECONDS)) {
                return new Resolved(
                        "bubblewrap",
                        bwrap.get(),
                        true,
                        false,
                        "Linux bubblewrap (read-only root, home masked, namespaces unshared; network "
                                + networkSummary()
                                + ").");
            }
            Optional<String> unshare = host.which("unshare");
            if (props.isSandboxAllowDegraded()
                    && unshare.isPresent()
                    && host.runsOk(
                            List.of(
                                    unshare.get(),
                                    "--user",
                                    "--map-root-user",
                                    "--pid",
                                    "--fork",
                                    "--kill-child",
                                    "--ipc",
                                    "--uts",
                                    "--",
                                    truePath),
                            PROBE_TIMEOUT_SECONDS)) {
                return new Resolved(
                        "unshare",
                        unshare.get(),
                        true,
                        true,
                        "DEGRADED: bubblewrap (bwrap) was not found or cannot run, so only"
                                + " PID/IPC/UTS"
                                + (props.getSandboxNetworkAllow().isEmpty() ? "/network" : "")
                                + " namespaces are applied by unshare. The filesystem is NOT"
                                + " confined. Install bubblewrap for real isolation.");
            }
            return unavailable(
                    "os",
                    "bubblewrap (bwrap) was not found or cannot create user namespaces here"
                            + (props.isSandboxAllowDegraded()
                                    ? ", and the unshare fallback does not work either."
                                    : " (the unshare fallback is disabled)")
                            + " Install it (apt install bubblewrap) or use sandbox=container.");
        }
        return unavailable(
                "os",
                "OS-level STDIO sandboxing is not supported on "
                        + (os.equals("windows") ? "Windows" : "this operating system")
                        + ". Use app.mcp.stdio.sandbox=container.");
    }

    private Resolved resolveContainer() {
        String want = props.getSandboxContainerRuntime();
        List<String> candidates =
                want.equals("docker") || want.equals("podman")
                        ? List.of(want)
                        : List.of("docker", "podman");
        for (String rt : candidates) {
            Optional<String> path = host.which(rt);
            if (path.isPresent()
                    && host.runsOk(
                            List.of(path.get(), "version", "--format", "{{.Server.Version}}"),
                            PROBE_TIMEOUT_SECONDS)) {
                return new Resolved(
                        rt,
                        path.get(),
                        true,
                        false,
                        rt
                                + " container (read-only rootfs, no-new-privileges, all capabilities"
                                + " dropped, memory/pids limits, data directory mount only; network "
                                + networkSummary()
                                + ").");
            }
        }
        return unavailable(
                "container",
                "No working container runtime ("
                        + String.join("/", candidates)
                        + ") was found: install it and make sure its daemon is running.");
    }

    private String networkSummary() {
        // Counts, never names: the status endpoint is readable by any authenticated user.
        List<String> allow = props.getSandboxNetworkAllow();
        if (allow.isEmpty()) {
            return "off for every server";
        }
        if (allow.stream().anyMatch(e -> e != null && e.strip().equals("*"))) {
            return "on for every server";
        }
        return "on only for " + allow.size() + " allowlisted server(s)";
    }

    private static Resolved unavailable(String kind, String detail) {
        return new Resolved(kind, null, false, false, detail);
    }

    private static String safeForLog(String name) {
        if (name == null) {
            return "?";
        }
        String t = name.length() > 64 ? name.substring(0, 64) : name;
        return t.replaceAll("[^A-Za-z0-9 _.:/@-]", "?");
    }
}
