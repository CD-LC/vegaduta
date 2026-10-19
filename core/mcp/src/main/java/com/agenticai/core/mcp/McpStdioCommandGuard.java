package com.agenticai.core.mcp;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Launcher allowlist for dynamic STDIO MCP registrations (MCP-DOCTOR
 * hardening, 2026-07-25). A STDIO registration's {@code command}/{@code
 * args}/{@code env} are tenant-typed free text that this platform then
 * executes as a real subprocess inside its own container - without this check
 * a tenant could register {@code bash -c ...}, {@code curl}, or an absolute
 * path to any binary in the image and run arbitrary code with core's
 * privileges.
 *
 * <p>Enforced at BOTH seams, fail-closed: registration save time ({@link
 * McpRegistryService#register}) and spawn time ({@code
 * DynamicMcpConnectionManager} in core/agents - its {@code stdioParamsFor},
 * the single place a {@code ServerParameters} is ever built for a dynamic
 * registration, calls {@link #requireAllowed(String, List, Map)} immediately
 * before handing command/args/env to the transport).
 *
 * <p>Rules:
 * <ul>
 *   <li>{@code command} must be a bare launcher name from the allowlist
 *       (default: npx, node, uvx, uv, python, python3 - extendable via
 *       {@code app.mcp.stdio-launcher-allowlist}). A Windows {@code .cmd}/
 *       {@code .exe} suffix on an allowed name is tolerated (the connect
 *       path itself rewrites {@code npx -> npx.cmd} on Windows - see
 *       {@code DynamicMcpConnectionManager#normalizeCommandForWindows}).</li>
 *   <li>No path separators in the command - {@code /usr/bin/env},
 *       {@code C:\...\anything.exe} are rejected; the launcher must resolve
 *       via PATH.</li>
 *   <li>No shell metacharacters in command or args (backtick, {@code &},
 *       {@code ;}, {@code |}, {@code <}, {@code >}, {@code $(}, newlines,
 *       NUL). ProcessBuilder never invokes a shell, so these could not
 *       actually chain commands today - this is defense-in-depth against
 *       any future path that ever hands these strings to one, and against
 *       launchers that do their own sub-shelling.</li>
 *   <li>No code-loading interpreter flags in args (see {@link
 *       #BLOCKED_LONG_FLAGS}).</li>
 *   <li>No interpreter/loader-control environment variables (see {@link
 *       #BLOCKED_ENV_NAMES}).</li>
 * </ul>
 *
 * <p>Note the registry mapper ({@code RegistryServerMapper}) can propose
 * {@code docker} for OCI-packaged registry servers - docker is deliberately
 * NOT in the default allowlist (docker run from inside the platform is
 * host-root-equivalent). An operator who accepts that risk can add it via
 * the property.
 */
@Component
public class McpStdioCommandGuard {

    static final List<String> DEFAULT_ALLOWLIST = List.of("npx", "node", "uvx", "uv", "python", "python3");

    private static final char[] FORBIDDEN_CHARS = {'`', '&', ';', '|', '<', '>', '\n', '\r', '\0'};

    // Pentest C1 (2026-07-25, Critical): the allowlist blocks bash/curl but every
    // allowed launcher is ALSO an interpreter that can eval inline code passed
    // in ARGS - `node -e '<js>'`, `node --print '<expr>'`, `python3 -c '<code>'`.
    // That's arbitrary code execution inside the core container and has no
    // legitimate MCP-server use (real servers are packages/entrypoints:
    // `npx <pkg>`, `uvx <pkg>`, `python -m <module>`, `node <script.js>`).
    //
    // Security audit 2026-07-29 (§2 item 2) - the original list stopped at the
    // five obvious eval flags and missed every MODULE-LOADING flag, which is
    // the same primitive by another name: `node --import <url>` and `node
    // --require <path>` evaluate the target module's top-level code before the
    // main entrypoint runs, and `data:text/javascript,<payload>` is an accepted
    // --import target, so no writable file is even needed. Same for the ESM
    // loader hooks. `--node-options` is npm/npx's own pass-through into the
    // node it spawns, i.e. an indirection to every flag in this list.
    //
    // `--inspect*` is here for a different reason: it does not run code by
    // itself, it opens a debugger port that speaks a protocol with a
    // Runtime.evaluate method. Anything that can reach that port (the whole
    // container network on `--inspect=0.0.0.0:9229`) has an eval endpoint.
    // Matched by PREFIX so the whole family (`--inspect-brk`, `--inspect-port`,
    // `--inspect-wait`, `--inspect-publish-uid`) is covered.
    //
    // Matched case-INsensitively: no allowed launcher accepts `--IMPORT`, so a
    // case variant can only ever be an evasion attempt, never a working arg.
    // (Short flags below are the opposite - see BLOCKED_SHORT_FLAGS.)
    //
    // NOTE this cannot stop the `npx <attacker-pkg>` / `uvx --from
    // <attacker-pkg>` supply-chain case - running published packages is the
    // whole point of dynamic STDIO MCP. That case is handled by the staff
    // review gate (McpReviewStatus), which a catalog-name reuse can no longer
    // skip (SEC-002, McpCatalogService#findStrictMatch). Index/registry
    // redirect env vars (PIP_INDEX_URL, UV_INDEX_URL, npm_config_registry)
    // ARE blocked since SEC-030: they would let an APPROVED or catalog-exact
    // spec resolve its vetted package name from an attacker index.
    static final Set<String> BLOCKED_LONG_FLAGS = Set.of(
        "--eval",
        "--command",
        "--print",
        "--import",
        "--require",
        "--loader",
        "--experimental-loader",
        "--node-options");

    /** Prefix-matched flag families - every member is dangerous, so enumerating them is pointless. */
    static final Set<String> BLOCKED_LONG_FLAG_PREFIXES = Set.of("--inspect");

    // Single-dash flags, matched case-SENSITIVELY and only within the leading
    // letter-run of a `-x...` token, because the uppercase spellings are real,
    // legitimate, and often the opposite of dangerous: `python -E` ignores env
    // vars, `python -P` is the safe-path hardening flag, `python -B` skips
    // bytecode. Blocking those would break hardened configs for zero gain.
    //
    //   'e' -> node -e / python -e-style inline eval
    //   'c' -> python -c, npm/npx -c (`npm exec -c '<shell command>'`)
    //
    // Both are matched even when a value is ATTACHED (`-cprint(1)`, `-e"..."`
    // arrives as one argv token) and inside a short-option CLUSTER
    // (`python -Bc '<code>'`, `npx -yc '<shell>'`) - the pre-2026-07-29 guard
    // compared whole tokens only and both of those walked straight through it.
    private static final Set<Character> BLOCKED_SHORT_FLAGS = Set.of('e', 'c');

    // Blocked ONLY when the launcher is literally `node`, where `-p` is
    // --print (eval) and `-r` is --require (module preload). They cannot be
    // global: `npx -p <pkg>` is --package, `uv/uvx -p <version>` is --python,
    // and `-r` is a perfectly ordinary flag for the MCP SERVER itself once
    // args stop being interpreter args (`npx some-mcp -r us-east-1`). This is
    // exactly the "flag means different things per launcher" trap the audit
    // finding's "-p (Node's alias for --print)" line names.
    private static final Set<Character> BLOCKED_SHORT_FLAGS_NODE_ONLY = Set.of('p', 'r');

    /**
     * Longest token treated as a short-option CLUSTER. `-Bc`, `-uc`, `-yc` are
     * real; a 7-letter single-dash token is a long option in Go/Java style
     * (`-verbose`, `-config`) belonging to the MCP server, not a cluster - and
     * flagging those on a stray 'e' would be a pure false positive.
     */
    private static final int MAX_SHORT_CLUSTER_LENGTH = 6;

    /**
     * ENV POLICY - DENYLIST, deliberately, not an allowlist.
     *
     * <p>An allowlist of env NAMES is not implementable here: an MCP server's
     * env is where its own third-party credentials live, and those names are
     * unbounded and defined by people we have never met -
     * {@code GITHUB_PERSONAL_ACCESS_TOKEN}, {@code BRAVE_API_KEY}, {@code
     * SLACK_TEAM_ID}, {@code MCP_POSTGRES_CONNECTION_STRING} in our own
     * catalog, plus whatever {@code RegistryEnvVarSpec} the official registry
     * hands us for a server published tomorrow. An allowlist would reject
     * every new server on the day it ships, so operators would widen it to
     * everything and we would be back here.
     *
     * <p>What IS closed-ended is the other direction: the set of variables
     * that make a RUNTIME load code. The child's runtime is bounded by the
     * launcher allowlist (node/npm, python/uv) plus whatever the image's
     * dynamic linker and any shell-out gadget provide - all well-known,
     * documented, and none of them a credential. So the policy is a hybrid:
     *
     * <ol>
     *   <li>a SHAPE allowlist on the name ({@link #ENV_NAME}) - a POSIX env
     *       name and nothing else, which also rejects shellshock-style
     *       function exports ({@code BASH_FUNC_x%%});</li>
     *   <li>this exact-name denylist, matched case-INsensitively because
     *       Windows env lookup is case-insensitive (so {@code node_options}
     *       is the same variable there) and because no legitimate MCP server
     *       uses a lowercase spelling of any of these;</li>
     *   <li>a PREFIX denylist ({@link #BLOCKED_ENV_PREFIXES}) for the
     *       families where no legitimate name exists at all.</li>
     * </ol>
     *
     * <p>SEC-030 (2026-09-30) widened the denylist to whole families where a
     * family is dangerous: every {@code NODE_*} and {@code PYTHON*} variable
     * (TLS trust stores, warning/coverage/compile-cache file writes, module
     * paths, breakpoint hooks), every npm/uv/pip/corepack config variable
     * (registry/index redirection, config-file and prefix/cache relocation),
     * OpenSSL config/engine/module loading, CA-bundle overrides, proxy
     * redirection (which would route the child around egress controls), and
     * PATH/HOME. Mirrors services/mcp-stdio-runner/server.js, the second
     * enforcement point (that list must be extended to match - core is the
     * stricter side and runs first).
     *
     * <p>Deliberately NOT blocked (see {@link #ALLOWED_ENV_EXCEPTIONS}):
     * {@code PYTHONUNBUFFERED} / {@code PYTHONIOENCODING} /
     * {@code PYTHONDONTWRITEBYTECODE} / {@code PYTHONUTF8} (routinely and
     * correctly set on STDIO servers, and inert), {@code NODE_ENV} and
     * {@code NODE_NO_WARNINGS} (ubiquitous and inert). Known over-block: a
     * credential whose name happens to start with {@code PYTHON} or
     * {@code NODE_} is rejected - rename it.
     */
    static final Set<String> BLOCKED_ENV_NAMES = Set.of(
        // --- shell gadgets: npm runs lifecycle scripts through a shell ---
        "BASH_ENV",                     // sourced by non-interactive bash
        "ENV",                          // the POSIX sh equivalent
        "SHELLOPTS",
        // --- interpreters that exist in the image even though they are not allowed launchers ---
        "PERL5OPT",                     // -e by another name
        "PERL5LIB",
        "RUBYOPT",
        // --- the image ships a JVM; all three inject -javaagent into ANY jvm started ---
        "JAVA_TOOL_OPTIONS",
        "_JAVA_OPTIONS",
        "JDK_JAVA_OPTIONS",
        // --- git runs during npm installs; these are exec-a-command-by-env vars ---
        "GIT_SSH",
        "GIT_SSH_COMMAND",
        "GIT_EXTERNAL_DIFF",
        "GIT_ASKPASS",
        "SSH_ASKPASS",
        "GIT_PROXY_COMMAND",
        "GIT_EXEC_PATH",
        "GIT_TEMPLATE_DIR",
        // --- TLS trust: replacing the CA set enables silent MITM of the child's traffic ---
        "SSL_CERT_FILE",
        "SSL_CERT_DIR",
        "SSLKEYLOGFILE",
        "REQUESTS_CA_BUNDLE",
        "CURL_CA_BUNDLE",
        // --- proxy redirection bypasses egress controls (lowercase spellings match too) ---
        "HTTP_PROXY",
        "HTTPS_PROXY",
        "ALL_PROXY",
        "NO_PROXY",
        "FTP_PROXY",
        // --- glibc: resolver redirection and loader-level code/tunable injection ---
        "HOSTALIASES",
        "RES_OPTIONS",
        "LOCALDOMAIN",
        "GCONV_PATH",
        "GLIBC_TUNABLES",
        // --- PATH is what the launcher allowlist RESTS on ---
        // The command rule above ("bare launcher name, resolved via PATH") is
        // only meaningful if PATH itself is trustworthy; letting a registration
        // set it means `command: node` can resolve to anything the tenant can
        // get written into the container (the filesystem MCP server writes to
        // core/mcp-data/). The transport supplies a correct PATH already
        // (ServerParameters.DEFAULT_INHERITED_ENV_VARS), so no legitimate
        // registration needs to. HOME relocates every dotfile config
        // (.npmrc, pip.conf, uv.toml) the launchers read.
        "PATH",
        "HOME");

    /**
     * Whole families with no legitimate member (minus {@link
     * #ALLOWED_ENV_EXCEPTIONS}), matched case-insensitively on the upper-cased
     * name. {@code LD_*}/{@code DYLD_*} are the dynamic linker's own controls;
     * {@code BASH_FUNC_*} is an exported shell function body; {@code NODE_*}
     * covers NODE_OPTIONS, NODE_PATH, NODE_EXTRA_CA_CERTS,
     * NODE_TLS_REJECT_UNAUTHORIZED, NODE_REDIRECT_WARNINGS, NODE_V8_COVERAGE,
     * NODE_COMPILE_CACHE, NODE_REPL_EXTERNAL_MODULE and whatever Node adds
     * next; {@code PYTHON} covers PYTHONPATH, PYTHONSTARTUP, PYTHONBREAKPOINT,
     * PYTHONHOME, PYTHONINSPECT, PYTHONWARNINGS and the PYTHON_* family;
     * {@code NPM_CONFIG_} covers every npm config (userconfig, globalconfig,
     * registry, prefix, cache, node-options, script-shell, proxy);
     * {@code UV_}/{@code PIP_} cover index/config/cache redirection;
     * {@code OPENSSL_} covers OPENSSL_CONF (engine loading = code
     * execution), OPENSSL_ENGINES and OPENSSL_MODULES; {@code COREPACK_}
     * (package-manager download registry); {@code XDG_} (relocates the config
     * files npm/uv/pip read); {@code GIT_CONFIG} (GIT_CONFIG_GLOBAL,
     * GIT_CONFIG_COUNT/KEY_n/VALUE_n can set core.sshCommand);
     * {@code GIT_SSL} (TLS verification); {@code GLOBAL_AGENT_} (the
     * global-agent proxy library).
     */
    static final Set<String> BLOCKED_ENV_PREFIXES = Set.of(
        "LD_", "DYLD_", "BASH_FUNC_",
        "NODE_", "PYTHON", "NPM_CONFIG_", "UV_", "PIP_", "OPENSSL_", "COREPACK_",
        "XDG_", "GIT_CONFIG", "GIT_SSL", "GLOBAL_AGENT_");

    /** Inert members of a blocked prefix family that STDIO servers routinely and legitimately set. */
    static final Set<String> ALLOWED_ENV_EXCEPTIONS = Set.of(
        "NODE_ENV", "NODE_NO_WARNINGS",
        "PYTHONUNBUFFERED", "PYTHONIOENCODING", "PYTHONDONTWRITEBYTECODE", "PYTHONUTF8");

    /** POSIX environment-variable name. Anything else is malformed, not a credential. */
    private static final Pattern ENV_NAME = Pattern.compile("^[A-Za-z_][A-Za-z0-9_]*$");

    private final List<String> allowlist;

    public McpStdioCommandGuard() {
        this.allowlist = List.copyOf(DEFAULT_ALLOWLIST);
    }

    // @Autowired: with the no-arg ctor above also present, Spring would otherwise
    // fall back to it and silently ignore app.mcp.stdio-launcher-allowlist.
    @org.springframework.beans.factory.annotation.Autowired
    public McpStdioCommandGuard(@Value("${app.mcp.stdio-launcher-allowlist:}") String extraAllowlist) {
        List<String> extras = extraAllowlist == null || extraAllowlist.isBlank()
            ? List.of()
            : Arrays.stream(extraAllowlist.split(",")).map(String::trim).filter(s -> !s.isEmpty())
                .map(s -> s.toLowerCase(Locale.ROOT)).toList();
        java.util.ArrayList<String> all = new java.util.ArrayList<>(DEFAULT_ALLOWLIST);
        all.addAll(extras);
        this.allowlist = List.copyOf(all);
    }

    /** Test seam - exact allowlist, no property resolution. */
    public static McpStdioCommandGuard withAllowlist(List<String> allowlist) {
        return new McpStdioCommandGuard(allowlist, true);
    }

    private McpStdioCommandGuard(List<String> allowlist, boolean exact) {
        this.allowlist = allowlist.stream().map(s -> s.toLowerCase(Locale.ROOT)).toList();
    }

    /**
     * Command + args only. Retained for callers that genuinely have no env to
     * check ({@code McpStdioSelfHealer} validates a proposed command/args pair
     * against an otherwise-unchanged registration) - every path that actually
     * SPAWNS must use the 3-arg overload.
     */
    public Optional<String> check(String command, List<String> args) {
        return check(command, args, null);
    }

    /** Empty when command+args+env are allowed; otherwise a clear, user-facing reason. Never throws. */
    public Optional<String> check(String command, List<String> args, Map<String, String> env) {
        if (command == null || command.isBlank()) {
            return Optional.of("STDIO server command is empty.");
        }
        String cmd = command.trim();
        if (cmd.contains("/") || cmd.contains("\\")) {
            return Optional.of("Command must be a bare launcher name resolved via PATH (e.g. 'npx'), not a path: '" + cmd + "'.");
        }
        Optional<String> meta = findForbidden(cmd);
        if (meta.isPresent()) {
            return Optional.of("Command contains a forbidden shell character (" + meta.get() + ").");
        }
        if (cmd.chars().anyMatch(Character::isWhitespace)) {
            return Optional.of("Command must be a single launcher token - put everything after the launcher into args.");
        }
        String base = baseLauncherOf(cmd);
        if (!allowlist.contains(base)) {
            return Optional.of("Launcher '" + cmd + "' is not allowed. Allowed launchers: "
                + String.join(", ", allowlist)
                + ". (Configurable via app.mcp.stdio-launcher-allowlist.)");
        }
        if (args != null) {
            for (String arg : args) {
                if (arg == null) {
                    return Optional.of("Args must not contain null entries.");
                }
                Optional<String> bad = findForbidden(arg);
                if (bad.isPresent()) {
                    return Optional.of("Arg '" + previewOf(arg) + "' contains a forbidden shell character (" + bad.get() + ").");
                }
                if (arg.contains("$(")) {
                    return Optional.of("Arg '" + previewOf(arg) + "' contains a forbidden shell substitution ($().");
                }
                Optional<String> blockedFlag = blockedFlagIn(arg, base);
                if (blockedFlag.isPresent()) {
                    return Optional.of("Arg '" + previewOf(arg) + "' is an inline-code/module-loading flag ("
                        + blockedFlag.get() + "), which would run arbitrary code. MCP servers must be a package or "
                        + "script entrypoint, not inline code.");
                }
            }
        }
        if (cmd.contains("$(")) {
            return Optional.of("Command contains a forbidden shell substitution ($().");
        }
        return checkEnv(env);
    }

    /**
     * Env-only half of the guard - see {@link #BLOCKED_ENV_NAMES} for the
     * denylist-vs-allowlist reasoning. Empty when every entry is safe; never
     * throws. Public so the spawn path can enforce env even on a code path
     * that already validated command/args.
     */
    public Optional<String> checkEnv(Map<String, String> env) {
        if (env == null || env.isEmpty()) {
            return Optional.empty();
        }
        for (Map.Entry<String, String> entry : env.entrySet()) {
            String name = entry.getKey();
            if (name == null || name.isBlank()) {
                return Optional.of("Environment variable names must not be empty.");
            }
            if (!ENV_NAME.matcher(name).matches()) {
                return Optional.of("Environment variable name '" + previewOf(name)
                    + "' is not a valid name (letters, digits and underscore only, not starting with a digit).");
            }
            if (isBlockedEnvName(name)) {
                return Optional.of(envRejection(name));
            }
            String value = entry.getValue();
            if (value == null) {
                return Optional.of("Environment variable '" + name + "' has no value - remove it or give it a value.");
            }
            if (value.indexOf('\0') >= 0) {
                // Values legitimately contain newlines (PEM keys, service-account
                // JSON), so only NUL - which is illegal in a real environment
                // block anyway - is rejected here.
                return Optional.of("Environment variable '" + name + "' contains a NUL character.");
            }
        }
        return Optional.empty();
    }

    /**
     * Whether {@code name} is a runtime-control variable (exact denylist or a
     * blocked family, case-insensitive, minus {@link #ALLOWED_ENV_EXCEPTIONS}).
     * Public so other seams can apply the identical policy.
     */
    public static boolean isBlockedEnvName(String name) {
        if (name == null) {
            return true;
        }
        String upper = name.toUpperCase(Locale.ROOT);
        if (ALLOWED_ENV_EXCEPTIONS.contains(upper)) {
            return false;
        }
        if (BLOCKED_ENV_NAMES.contains(upper)) {
            return true;
        }
        for (String prefix : BLOCKED_ENV_PREFIXES) {
            if (upper.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    private static String envRejection(String name) {
        return "Environment variable '" + name + "' is not allowed - it controls how the runtime loads code "
            + "(e.g. NODE_OPTIONS=--import <url> runs arbitrary JavaScript in the MCP server process). "
            + "Only pass the server's own credentials and configuration.";
    }

    /** Save/spawn-time enforcement - throws with the user-facing reason (mapped to HTTP 400 by GlobalExceptionHandler). */
    public void requireAllowed(String command, List<String> args) {
        requireAllowed(command, args, null);
    }

    /** Save/spawn-time enforcement including env - throws with the user-facing reason. */
    public void requireAllowed(String command, List<String> args, Map<String, String> env) {
        check(command, args, env).ifPresent(reason -> {
            throw new IllegalArgumentException(reason);
        });
    }

    /** Launcher name with the Windows {@code .cmd}/{@code .exe} suffix stripped, lowercased. */
    private static String baseLauncherOf(String cmd) {
        String base = cmd.toLowerCase(Locale.ROOT);
        if (base.endsWith(".cmd") || base.endsWith(".exe")) {
            base = base.substring(0, base.length() - 4);
        }
        return base;
    }

    /**
     * The blocked flag inside {@code arg}, if any. Handles every spelling an
     * interpreter actually accepts:
     * <ul>
     *   <li>{@code --import X} and {@code --import=X} (split at the first '=',
     *       case-insensitive - see {@link #BLOCKED_LONG_FLAGS});</li>
     *   <li>{@code --inspect}, {@code --inspect-brk=...} (prefix family);</li>
     *   <li>{@code -c}, {@code -c=X}, and the ATTACHED form {@code -cprint(1)};</li>
     *   <li>short-option CLUSTERS, {@code python -Bc '<code>'} / {@code npx -yc}.</li>
     * </ul>
     * Never substring-matches: a path arg such as {@code ./bin/server-r.js} or
     * {@code --data-dir=/opt/mcp-r} does not start with a dash at all, and a
     * long single-dash token like {@code -verbose} is left alone unless its
     * FIRST letter is itself a blocked flag (an attached-value form).
     *
     * <p><b>Known, accepted over-block:</b> a single-dash option belonging to
     * the MCP SERVER whose first letter collides with a blocked short flag -
     * {@code -config}, {@code -endpoint} - is rejected, because an attached
     * payload ({@code -cprint(1)}, {@code -e"code"}) is indistinguishable from
     * it by construction and the eval form has to win. The workaround is the
     * two-dash spelling ({@code --config}), which is unaffected. Covered by
     * {@code knownAndAcceptedOverBlock_singleDashOptionsStartingWithABlockedLetter}.
     */
    private static Optional<String> blockedFlagIn(String arg, String baseLauncher) {
        if (arg.length() < 2 || arg.charAt(0) != '-') {
            return Optional.empty();
        }
        int eq = arg.indexOf('=');
        String token = eq >= 0 ? arg.substring(0, eq) : arg;
        if (token.startsWith("--")) {
            String lower = token.toLowerCase(Locale.ROOT);
            if (BLOCKED_LONG_FLAGS.contains(lower)) {
                return Optional.of(token);
            }
            for (String prefix : BLOCKED_LONG_FLAG_PREFIXES) {
                if (lower.startsWith(prefix)) {
                    return Optional.of(token);
                }
            }
            return Optional.empty();
        }
        Set<Character> blocked = shortFlagsFor(baseLauncher);
        String run = leadingLetters(token);
        if (run.isEmpty()) {
            return Optional.empty();
        }
        if (blocked.contains(run.charAt(0))) {
            return Optional.of("-" + run.charAt(0));
        }
        boolean wholeTokenIsLetters = run.length() == token.length() - 1;
        if (wholeTokenIsLetters && run.length() <= MAX_SHORT_CLUSTER_LENGTH) {
            for (int i = 1; i < run.length(); i++) {
                if (blocked.contains(run.charAt(i))) {
                    return Optional.of("-" + run.charAt(i) + " (in the cluster '" + token + "')");
                }
            }
        }
        return Optional.empty();
    }

    private static Set<Character> shortFlagsFor(String baseLauncher) {
        if (!"node".equals(baseLauncher)) {
            return BLOCKED_SHORT_FLAGS;
        }
        Set<Character> merged = new LinkedHashSet<>(BLOCKED_SHORT_FLAGS);
        merged.addAll(BLOCKED_SHORT_FLAGS_NODE_ONLY);
        return merged;
    }

    /** ASCII letters immediately after the leading '-', e.g. {@code -Bc} -> "Bc", {@code -1} -> "". */
    private static String leadingLetters(String token) {
        int i = 1;
        while (i < token.length() && isAsciiLetter(token.charAt(i))) {
            i++;
        }
        return token.substring(1, i);
    }

    private static boolean isAsciiLetter(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z');
    }

    private static Optional<String> findForbidden(String value) {
        for (char c : FORBIDDEN_CHARS) {
            if (value.indexOf(c) >= 0) {
                return Optional.of(c == '\n' || c == '\r' ? "newline" : c == '\0' ? "NUL" : "'" + c + "'");
            }
        }
        return Optional.empty();
    }

    private static String previewOf(String arg) {
        String flat = arg.replace('\n', ' ').replace('\r', ' ');
        return flat.length() > 40 ? flat.substring(0, 40) + "..." : flat;
    }
}
