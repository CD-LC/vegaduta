package com.agenticai.core.agents.tools;

import com.agenticai.core.agents.tools.stdiosandbox.SandboxLaunch;
import com.agenticai.core.agents.tools.stdiosandbox.StdioSandboxRuntime;
import com.agenticai.core.agents.tools.stdiosandbox.StdioSandboxService;
import com.agenticai.core.json.JsonMappers;
import com.agenticai.core.mcp.McpStdioCommandGuard;
import com.agenticai.core.mcp.dto.McpDynamicConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.client.transport.ServerParameters;
import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.nio.file.attribute.UserPrincipal;
import java.nio.file.attribute.UserPrincipalNotFoundException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * STDIO {@code ServerParameters} construction (env chokepoint, per-registration package-cache
 * isolation, sidecar wrapping) extracted from {@link DynamicMcpConnectionManager} - behaviour
 * unchanged.
 */
final class McpStdioParamsBuilder {

    private static final Logger log = LoggerFactory.getLogger(McpStdioParamsBuilder.class);
    private static final ObjectMapper JSON_MAPPER = JsonMappers.plain();
    private static final Set<PosixFilePermission> OWNER_ONLY =
            PosixFilePermissions.fromString("rwx------");

    /**
     * Sub-roots under the package-cache base ({@code app.mcp.package-cache-dir}); each holds one
     * {@code <cacheKey>} directory per stdio spawn - see {@link #isolatedPackageCacheEnv(Path,
     * String, Map)}. {@link McpPackageCacheJanitor} sweeps exactly these.
     */
    static final String NPM_CACHE_ROOT = "npm-cache";

    static final String UV_CACHE_ROOT = "uv-cache";
    static final String UV_TOOLS_ROOT = "uv-tools";
    static final String UV_BIN_ROOT = "uv-bin";
    static final List<String> ISOLATED_CACHE_ROOTS =
            List.of(NPM_CACHE_ROOT, UV_CACHE_ROOT, UV_TOOLS_ROOT, UV_BIN_ROOT);

    private final McpStdioCommandGuard stdioCommandGuard;
    private final McpStdioSidecarProperties sidecarProperties;
    private final Path packageCacheBase;

    /** Test seam; null = use whatever {@link StdioSandboxRuntime} has installed (usually none). */
    private final StdioSandboxService sandboxOverride;

    /**
     * @param packageCacheBase the resolved, owner-only base directory from {@link
     *     #resolvePackageCacheDir}; every per-registration cache lives under it
     */
    McpStdioParamsBuilder(
            McpStdioCommandGuard stdioCommandGuard,
            McpStdioSidecarProperties sidecarProperties,
            Path packageCacheBase) {
        this(stdioCommandGuard, sidecarProperties, packageCacheBase, null);
    }

    McpStdioParamsBuilder(
            McpStdioCommandGuard stdioCommandGuard,
            McpStdioSidecarProperties sidecarProperties,
            Path packageCacheBase,
            StdioSandboxService sandboxOverride) {
        this.stdioCommandGuard = stdioCommandGuard;
        this.sidecarProperties = sidecarProperties;
        this.packageCacheBase = packageCacheBase;
        this.sandboxOverride = sandboxOverride;
    }

    /**
     * Resolves {@code app.mcp.package-cache-dir} ({@code MCP_PACKAGE_CACHE_DIR}) to the owner-only
     * base directory every per-registration npm/uv cache is created under - never the shared,
     * world-writable system temp root with a predictable name, where another local user could
     * pre-plant a cache (packages that then get executed) or swap a directory (java:S5443). Same
     * mechanism as {@code LocalCodingSandbox#privateParent}: created {@code rwx------}, resolved to
     * its real path, refused when it is a symlink, not a directory or (on POSIX) owned by someone
     * other than the process user, and forced back to {@code rwx------}.
     *
     * <p>Empty/blank = {@code ~/.vegaduta/mcp-package-cache} of the user running core. In the
     * containers (core/Dockerfile runs as {@code agentic}, and the Compose/Helm deployments mount a
     * read-only root FS with only {@code /tmp}, {@code ~/.npm} and {@code ~/.cache/uv} writable)
     * that home directory cannot be created, so the DEFAULT - and only the default; an explicitly
     * configured directory that is unusable fails core's start-up - falls back to an owner-verified
     * {@code vegaduta-mcp-package-cache-<user>} directory under {@code java.io.tmpdir} (a
     * container-private tmpfs there, the pre-existing behaviour) and logs how to make the fallback
     * unnecessary: mount an owner-only writable directory and point {@code MCP_PACKAGE_CACHE_DIR}
     * at it. The fallback is still a hotspot on a shared bare-metal host, which is why the
     * ownership and permission checks above apply to it too.
     */
    static Path resolvePackageCacheDir(String configured) {
        boolean explicit = configured != null && !configured.isBlank();
        Path requested = explicit ? Path.of(configured.strip()) : defaultPackageCacheDir();
        try {
            return privateDirectory(requested);
        } catch (IOException e) {
            if (explicit) {
                throw new IllegalStateException(
                        "app.mcp.package-cache-dir (MCP_PACKAGE_CACHE_DIR) is not usable: "
                                + requested,
                        e);
            }
            String tmp = System.getProperty("java.io.tmpdir");
            Path fallback =
                    Path.of(tmp, "vegaduta-mcp-package-cache-" + System.getProperty("user.name"));
            try {
                Path resolved = privateDirectory(fallback);
                log.warn(
                        "MCP package cache: {} is not usable ({}); using {} instead. Mount an"
                                + " owner-only writable directory and set MCP_PACKAGE_CACHE_DIR"
                                + " to it.",
                        requested,
                        e.getMessage(),
                        resolved);
                return resolved;
            } catch (IOException fallbackFailure) {
                fallbackFailure.addSuppressed(e);
                throw new IllegalStateException(
                        "No usable MCP package cache directory: set MCP_PACKAGE_CACHE_DIR to an"
                                + " owner-only writable directory (tried "
                                + requested
                                + " and "
                                + fallback
                                + ")",
                        fallbackFailure);
            }
        }
    }

    private static Path defaultPackageCacheDir() {
        return Path.of(System.getProperty("user.home"), ".vegaduta", "mcp-package-cache");
    }

    private static Path privateDirectory(Path requested) throws IOException {
        Path dir = requested.toAbsolutePath().normalize();
        if (Files.isSymbolicLink(dir)) {
            throw new IOException("Refusing MCP package cache dir " + dir + ": symbolic link");
        }
        Files.createDirectories(dir, ownerOnly());
        Path real = dir.toRealPath();
        if (!Files.isDirectory(real)) {
            throw new IOException("Not a directory: " + dir);
        }
        if (posix()) {
            UserPrincipal owner = Files.getOwner(real);
            UserPrincipal self = currentUser();
            if (self != null && !owner.equals(self)) {
                throw new IOException(
                        "Refusing MCP package cache dir "
                                + real
                                + ": owned by "
                                + owner.getName()
                                + ", not "
                                + self.getName());
            }
            if (!OWNER_ONLY.equals(Files.getPosixFilePermissions(real))) {
                Files.setPosixFilePermissions(real, OWNER_ONLY);
            }
        }
        return real;
    }

    private static boolean posix() {
        return FileSystems.getDefault().supportedFileAttributeViews().contains("posix");
    }

    private static FileAttribute<?>[] ownerOnly() {
        return posix()
                ? new FileAttribute<?>[] {PosixFilePermissions.asFileAttribute(OWNER_ONLY)}
                : new FileAttribute<?>[0];
    }

    /**
     * The process user, or {@code null} when it has no passwd entry (an arbitrary container uid).
     */
    private static UserPrincipal currentUser() throws IOException {
        try {
            var lookup = FileSystems.getDefault().getUserPrincipalLookupService();
            return lookup.lookupPrincipalByName(System.getProperty("user.name"));
        } catch (UserPrincipalNotFoundException e) {
            return null;
        }
    }

    /**
     * Direct feedback: "its enabled and not picking this is the issue with other mcp also" - every
     * credential-gated catalog entry (github/slack/postgres/etc.) suggests a plain {@code "npx"}
     * command, same as the static, zero-credential servers in application-mcp.yml. That file's own
     * comment already documents why literal {@code "npx"} fails to spawn on Windows: the MCP SDK's
     * stdio transport builds its subprocess with a plain {@code ProcessBuilder}, which calls
     * Windows' {@code CreateProcess} directly, which - unlike {@code cmd.exe} - never consults
     * {@code PATHEXT} for an extensionless command, and npm installs the shim as {@code npx.cmd},
     * not {@code npx.exe}, on Windows. That fix was previously only applied to the six static YAML
     * entries (via {@code run-core.ps1} now setting {@code MCP_*_COMMAND=npx.cmd}); a tenant-typed
     * or catalog-prefilled DYNAMIC registration's {@code command} column goes through this class
     * instead, so it needed the identical normalization here to actually connect on Windows too.
     * Left alone for anything that isn't literally {@code "npx"} (a different runtime, an absolute
     * path, {@code "npx.cmd"} already, etc.) so this never rewrites something a tenant deliberately
     * typed.
     */
    private static String normalizeCommandForWindows(String command) {
        boolean windows =
                System.getProperty("os.name", "")
                        .toLowerCase(java.util.Locale.ROOT)
                        .contains("win");
        return windows && "npx".equals(command) ? "npx.cmd" : command;
    }

    /**
     * Shared ServerParameters build for connect() and test() - args/env null-tolerant, Windows npx
     * normalization applied.
     *
     * <p><b>Security audit 2026-07-29 (§2 item 2) - the env chokepoint.</b> This is the ONLY place
     * in the codebase where a dynamic registration's command/args/env become a {@code
     * ServerParameters}, i.e. the last line before the SDK's {@code ProcessBuilder} spawns the
     * child with them (see {@link EnvIsolatedStdioClientTransport}, which clears the inherited
     * parent env so what the SDK then {@code putAll}s - exactly this map - is all the child gets).
     * Re-running the full guard HERE, rather than only at the two callers, is what makes the env
     * rule unbypassable: any future spawn path must come through this method to get a {@code
     * ServerParameters} at all, and an env carrying {@code NODE_OPTIONS=--import
     * data:text/javascript,<payload>} - which touches none of the command/args rules - now cannot
     * reach the process builder. Instance (was static) purely so the guard bean is reachable.
     *
     * <p>Throwing here is safe for both callers' contracts: {@code connect()} and {@code test()}
     * each already wrap this in {@code catch (Throwable)} and turn it into a persisted SPAWN-phase
     * diagnosis, so "never throws" still holds at their boundaries.
     */
    ServerParameters stdioParamsFor(McpDynamicConfig config) {
        stdioCommandGuard.requireAllowed(config.command(), config.args(), config.env());
        String command = config.command();
        List<String> args = config.args() == null ? List.of() : config.args();
        Map<String, String> env = isolatedPackageCacheEnv(config);
        if (sidecarProperties.isEnabled()) {
            // The guard above already approved the REAL command/args/env - this
            // wraps the ALREADY-APPROVED target for out-of-process execution,
            // it does not change what was approved. See wrapForSidecar's javadoc.
            return wrapForSidecar(command, args, env);
        }
        // app.mcp.stdio.sandbox: wraps the same approved command in an OS sandbox / container.
        // Off (the default) -> empty and the plain spawn below is exactly what it always was.
        StdioSandboxService sandbox =
                sandboxOverride != null ? sandboxOverride : StdioSandboxRuntime.current();
        if (sandbox != null && sandbox.enabled()) {
            Path dataDir =
                    packageCacheBase
                            .resolve(NPM_CACHE_ROOT)
                            .resolve(String.valueOf(config.registrationId()))
                            .resolve("sandbox");
            java.util.Optional<SandboxLaunch> launch =
                    sandbox.wrap(config.name(), command, args, env, dataDir, packageCacheBase);
            if (launch.isPresent()) {
                SandboxLaunch l = launch.get();
                return ServerParameters.builder(l.command()).args(l.args()).env(l.env()).build();
            }
        }
        return ServerParameters.builder(normalizeCommandForWindows(command))
                .args(args)
                .env(env)
                .build();
    }

    /**
     * Live incident, 2026-08-12/13: stdio MCP servers spawned via {@code npx -y <pkg>} (github,
     * mockscreenshots-mcp, and others) intermittently failed to connect at all - {@code "npm error
     * EACCES ... It is likely you do not have the permissions to access this file as the current
     * user"} against {@code /home/agentic/.npm}, plus a follow-on {@code "Log files were not
     * written due to an error writing to the directory: /home/agentic/.npm/_logs"}.
     * core/Dockerfile's own {@code chown -R agentic:agentic /home/agentic} (see its comment) covers
     * the packages baked in at BUILD time (playwright, @playwright/mcp) - it does nothing for the
     * shared npm/uv caches every OTHER stdio server's runtime {@code npx}/{@code uvx} lazily
     * creates for the first time, all writing into the SAME {@code $HOME/.npm} the moment several
     * MCP connections spawn concurrently at boot (this manager's every child process runs as the
     * same {@code agentic} uid, so this was never a real ownership mismatch - it was concurrent
     * first-writers racing to create the same shared cache tree).
     *
     * <p>Fixed at the same "env chokepoint" {@link #stdioParamsFor}'s own javadoc already documents
     * (the last stop before {@code ServerParameters} reaches the SDK's {@code ProcessBuilder}, and
     * {@link EnvIsolatedStdioClientTransport} already wipes the child's inherited env down to
     * exactly this map): give every stdio spawn its OWN cache directory, keyed by registration id,
     * instead of sharing one mutable tree across every concurrently-spawned server. A directory a
     * process creates for itself on first write can never be root-owned or raced by a sibling spawn
     * - this removes the whole defect class, not just today's two observed packages. {@code
     * putIfAbsent} so a registration's own explicit env (rare, but real) still wins.
     *
     * <p>Follow-up, 2026-08-13 (live report - a {@code uvx}-launched server, e.g. a2atlassian,
     * failed with {@code "failed to create directory '/home/agentic/.local/share/uv/tools':
     * Permission denied"}): {@code UV_CACHE_DIR} above only covers {@code uv}'s package-download
     * cache - {@code uvx} (an alias for {@code uv tool run}) separately installs the tool's
     * environment under {@code UV_TOOL_DIR} (default {@code $XDG_DATA_HOME/uv/tools}, i.e. exactly
     * the failing path when unset), and symlinks its entrypoint under {@code UV_TOOL_BIN_DIR}
     * (default {@code ~/.local/bin}) - two more shared, non-writable trees this container's {@code
     * agentic} user was never meant to write into. Same isolation mechanism, same reasoning: give
     * each registration its own directories.
     *
     * <p>Sonar follow-up (java:S5443): the directories used to sit at predictable names directly
     * under the shared {@code /tmp}; they now live under the owner-only base from {@link
     * #resolvePackageCacheDir}, in the same four sub-roots ({@link #ISOLATED_CACHE_ROOTS}) with the
     * same {@code <key>} layout, so the janitor's orphan sweep is unchanged.
     */
    Map<String, String> isolatedPackageCacheEnv(McpDynamicConfig config) {
        return isolatedPackageCacheEnv(
                packageCacheBase, String.valueOf(config.registrationId()), config.env());
    }

    /**
     * Key-based form of {@link #isolatedPackageCacheEnv(McpDynamicConfig)} - also used by {@link
     * StaticMcpStdioSidecarRouting} for the static (application-mcp.yml) built-ins, keyed {@code
     * static-<connection name>} so they can never collide with a registration id.
     */
    static Map<String, String> isolatedPackageCacheEnv(
            Path packageCacheBase, String cacheKey, Map<String, String> configuredEnv) {
        Map<String, String> configured = configuredEnv == null ? Map.of() : configuredEnv;
        Map<String, String> env = new java.util.LinkedHashMap<>(configured);
        env.putIfAbsent("npm_config_cache", cacheDir(packageCacheBase, NPM_CACHE_ROOT, cacheKey));
        env.putIfAbsent("UV_CACHE_DIR", cacheDir(packageCacheBase, UV_CACHE_ROOT, cacheKey));
        env.putIfAbsent("UV_TOOL_DIR", cacheDir(packageCacheBase, UV_TOOLS_ROOT, cacheKey));
        env.putIfAbsent("UV_TOOL_BIN_DIR", cacheDir(packageCacheBase, UV_BIN_ROOT, cacheKey));
        return env;
    }

    private static String cacheDir(Path packageCacheBase, String root, String cacheKey) {
        return packageCacheBase.resolve(root).resolve(cacheKey).toString();
    }

    /**
     * MCP-STDIO-SANDBOXING (2026-08-05): builds the {@code ServerParameters} for {@code core}'s own
     * local child process when routing a STDIO spawn through the {@code mcp-stdio-runner} sidecar -
     * see {@code docs/architecture/MCP-STDIO-SANDBOXING-DESIGN.md} §8 for the full design.
     *
     * <p>{@code core} does NOT spawn the real interpreter/package. It spawns {@code
     * core/mcp-stdio-proxy/proxy.js} (baked into this image at {@code
     * /opt/mcp-stdio-proxy/proxy.js}, see {@code core/Dockerfile}) - a small, fixed, trusted relay
     * that does nothing but forward bytes to and from the sidecar, which spawns the real target
     * INSIDE ITSELF instead. This is the entire isolation mechanism: the untrusted package's code
     * never executes in this container, in sidecar mode, full stop.
     *
     * <p>The relay reads its own instructions from its own process env (control vars below), which
     * - because this {@code ServerParameters} still goes through the exact same {@link
     * EnvIsolatedStdioClientTransport} every other STDIO spawn does - contains ONLY these five
     * control vars plus the SDK's non-secret inherited defaults. Since SEC-030 the tenant's {@code
     * realEnv} is NOT part of the relay's process environment: it is carried as JSON in {@code
     * MCP_SIDECAR_TARGET_ENV_JSON} and sent to the runner in the control frame, so no tenant-chosen
     * variable can configure the node process running inside core.
     *
     * <p>Deliberately pure (no I/O, no Spring context) so it's unit-testable the same way {@link
     * #redactSecrets} already is - see {@code DynamicMcpConnectionManagerTest}'s own stated scoping
     * rule for why that matters in this class.
     */
    ServerParameters wrapForSidecar(
            String realCommand, List<String> realArgs, Map<String, String> realEnv) {
        return wrapForSidecar(sidecarProperties, realCommand, realArgs, realEnv);
    }

    /**
     * Static form of {@link #wrapForSidecar(String, List, Map)} - shared with {@link
     * StaticMcpStdioSidecarRouting}.
     */
    static ServerParameters wrapForSidecar(
            McpStdioSidecarProperties sidecarProperties,
            String realCommand,
            List<String> realArgs,
            Map<String, String> realEnv) {
        // SEC-030: the relay is a real `node` process INSIDE core. It must never
        // run with the tenant's env as its own process environment (a NODE_* /
        // OPENSSL_* / proxy var the guard missed would configure core's own
        // node, not the sandboxed child). Its env is therefore ONLY the fixed
        // control vars below (plus the SDK's non-secret inherited defaults -
        // PATH/HOME/USER/...; EnvIsolatedStdioClientTransport strips the rest of
        // core's env). The tenant env travels as inert JSON data and proxy.js
        // puts it in the control frame for mcp-stdio-runner.
        Map<String, String> proxyEnv = new java.util.HashMap<>();
        proxyEnv.put("MCP_SIDECAR_URL", sidecarProperties.getAddress());
        proxyEnv.put("MCP_SIDECAR_SECRET", sidecarProperties.getSharedSecret());
        proxyEnv.put("MCP_SIDECAR_TARGET_COMMAND", realCommand);
        proxyEnv.put("MCP_SIDECAR_TARGET_ARGS_JSON", toJsonArray(realArgs));
        proxyEnv.put(TARGET_ENV_JSON_VAR, toJsonObject(targetEnvForRunner(realEnv)));
        return ServerParameters.builder("node")
                .args(List.of("/opt/mcp-stdio-proxy/proxy.js"))
                .env(proxyEnv)
                .build();
    }

    /**
     * Control var carrying the target's env as a JSON object (read by
     * core/mcp-stdio-proxy/proxy.js).
     */
    static final String TARGET_ENV_JSON_VAR = "MCP_SIDECAR_TARGET_ENV_JSON";

    /**
     * Names mcp-stdio-runner always sets itself (its RUNNER_OWNED_ENV: per- connection
     * HOME/TMP/npm/uv/pip/XDG dirs, its own PATH) plus the relay's control vars - never forwarded.
     * The platform-injected cache dirs from {@link #isolatedPackageCacheEnv} are among them, so the
     * runner's env denylist can block the whole npm_config_/UV_ families without refusing core's
     * own defaults.
     */
    private static final java.util.Set<String> NOT_FORWARDED_TO_RUNNER =
            java.util.Set.of(
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
                    "XDG_RUNTIME_DIR",
                    "MCP_SIDECAR_URL",
                    "MCP_SIDECAR_SECRET",
                    "MCP_SIDECAR_TARGET_COMMAND",
                    "MCP_SIDECAR_TARGET_ARGS_JSON",
                    TARGET_ENV_JSON_VAR);

    /**
     * The env the runner should hand the real child - {@code realEnv} minus runner-owned and
     * control names (any case).
     */
    static Map<String, String> targetEnvForRunner(Map<String, String> realEnv) {
        Map<String, String> out = new java.util.TreeMap<>();
        if (realEnv == null) {
            return out;
        }
        realEnv.forEach(
                (k, v) -> {
                    if (k != null
                            && v != null
                            && !NOT_FORWARDED_TO_RUNNER.contains(
                                    k.toUpperCase(java.util.Locale.ROOT))) {
                        out.put(k, v);
                    }
                });
        return out;
    }

    private static String toJsonObject(Map<String, String> values) {
        try {
            return JSON_MAPPER.writeValueAsString(values);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException(
                    "Failed to serialize STDIO env for the sidecar relay", e);
        }
    }

    private static String toJsonArray(List<String> values) {
        try {
            return JSON_MAPPER.writeValueAsString(values);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            // args are guard-approved strings (McpStdioCommandGuard already ran) -
            // this can only fail on a JVM-level Jackson misconfiguration, not on
            // any value a caller controls.
            throw new IllegalStateException(
                    "Failed to serialize STDIO args for the sidecar relay", e);
        }
    }
}
