package com.agenticai.core.mcp.registry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.agenticai.core.mcp.McpServerType;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Locks in {@link RegistryServerMapper}'s translation of the official
 * registry's raw package/remote schema into launch-ready registrations for
 * all four supported runtimes plus the not-runnable fallback - the one piece
 * of the registry integration with real branching logic, and the one whose
 * silent drift (a wrong arg order, a leaked secret in argv, an npx prompt
 * hang) would produce servers that register fine but never actually connect.
 */
class RegistryServerMapperTest {

    private final RegistryServerMapper mapper = new RegistryServerMapper();

    private static RegistryApiTypes.Server server(String name, List<RegistryApiTypes.Package> pkgs, List<RegistryApiTypes.Remote> remotes) {
        return new RegistryApiTypes.Server(name, "desc for " + name, null, "1.2.3",
            new RegistryApiTypes.Repository("https://github.com/x/y", "github", null), pkgs, remotes);
    }

    /** {@code map()} with the common {@code "active"} status - most tests don't care about lifecycle status. */
    private RegistrySearchResult map(RegistryApiTypes.Server server) {
        return mapper.map(server, "active");
    }

    private static RegistryApiTypes.Argument positional(String value) {
        return new RegistryApiTypes.Argument("positional", null, value, null, null, null, null);
    }

    @Test
    void npmPackageMapsToNpxWithYesFlagAndVersionedToken() {
        var pkg = new RegistryApiTypes.Package("npm", null, "@acme/weather-mcp", "2.0.0", "npx",
            new RegistryApiTypes.Transport("stdio", null), List.of(), List.of(), List.of());
        var result = map(server("io.github.acme/weather", List.of(pkg), null));

        assertEquals(McpServerType.STDIO, result.type());
        assertEquals("npx", result.runtime());
        assertTrue(result.runnable());
        assertEquals("npx", result.command());
        assertEquals(List.of("-y", "@acme/weather-mcp@2.0.0"), result.args());
        assertNull(result.url());
    }

    @Test
    void npmDoesNotDoubleTheYesFlagWhenRegistryAlreadyIncludesIt() {
        var pkg = new RegistryApiTypes.Package("npm", null, "pkg", null, "npx",
            new RegistryApiTypes.Transport("stdio", null), List.of(positional("-y")), List.of(), List.of());
        var result = map(server("com.x/pkg", List.of(pkg), null));
        assertEquals(1, result.args().stream().filter("-y"::equals).count());
        assertEquals(List.of("-y", "pkg"), result.args());
    }

    @Test
    void envVarsBecomeSpecsAndSecretsAreDetected() {
        var envs = List.of(
            new RegistryApiTypes.EnvVar("GITHUB_TOKEN", "a token", true, null, null),
            new RegistryApiTypes.EnvVar("REGION", "region", false, false, "us-east-1"));
        var pkg = new RegistryApiTypes.Package("npm", null, "gh", null, "npx",
            new RegistryApiTypes.Transport("stdio", null), List.of(), List.of(), envs);
        var result = map(server("com.x/gh", List.of(pkg), null));

        assertEquals(2, result.envVars().size());
        var token = result.envVars().get(0);
        assertEquals("GITHUB_TOKEN", token.name());
        assertTrue(token.required());
        assertTrue(token.secret(), "name containing 'token' must be treated as secret even when isSecret is null");
        var region = result.envVars().get(1);
        assertFalse(region.required());
        assertFalse(region.secret());
        assertEquals("us-east-1", region.defaultValue());
    }

    @Test
    void pypiPackageMapsToUvx() {
        var pkg = new RegistryApiTypes.Package("pypi", null, "mcp-server-git", "0.5.0", "uvx",
            new RegistryApiTypes.Transport("stdio", null), List.of(), List.of(), List.of());
        var result = map(server("io.github.x/git", List.of(pkg), null));
        assertEquals("uvx", result.runtime());
        assertTrue(result.runnable());
        assertEquals("uvx", result.command());
        assertEquals(List.of("mcp-server-git@0.5.0"), result.args());
    }

    @Test
    void ociPackageMapsToDockerRunWithEnvForwardingNotInlineSecrets() {
        var envs = List.of(new RegistryApiTypes.EnvVar("API_KEY", "key", true, true, null));
        var pkg = new RegistryApiTypes.Package("oci", null, "mcp/fetch", "1.0", "docker",
            new RegistryApiTypes.Transport("stdio", null), List.of(), List.of(), envs);
        var result = map(server("com.x/fetch", List.of(pkg), null));

        assertEquals("docker", result.command());
        // -e API_KEY (no =value) forwards from the launcher env; the secret
        // value must never appear literally in argv.
        assertEquals(List.of("run", "-i", "--rm", "-e", "API_KEY", "mcp/fetch:1.0"), result.args());
        assertFalse(result.args().stream().anyMatch(a -> a.contains("=")));
    }

    @Test
    void requiredValuelessArgumentBecomesPlaceholderAndPositionalSpec() {
        // e.g. postgres taking a connection string as a positional CLI arg.
        var connArg = new RegistryApiTypes.Argument("positional", null, null, "postgresql://...", null, true, null);
        var pkg = new RegistryApiTypes.Package("npm", null, "@mcp/postgres", null, "npx",
            new RegistryApiTypes.Transport("stdio", null), List.of(), List.of(connArg), List.of());
        var result = map(server("com.x/pg", List.of(pkg), null));

        assertTrue(result.args().contains("{{POSTGRESQL}}") || result.args().stream().anyMatch(a -> a.startsWith("{{")),
            "a required valueless arg must be spliced as a {{placeholder}}: " + result.args());
        assertTrue(result.envVars().stream().anyMatch(RegistryEnvVarSpec::positional),
            "the placeholder must produce a positional env-var spec for the Add form to collect");
    }

    @Test
    void remoteServerWithNoPackagesMapsToStreamableHttp() {
        var remote = new RegistryApiTypes.Remote("streamable-http", "https://mcp.example.com/sse");
        var result = map(server("com.example/hosted", null, List.of(remote)));
        assertEquals(McpServerType.STREAMABLE_HTTP, result.type());
        assertEquals("remote", result.runtime());
        assertTrue(result.runnable());
        assertNull(result.command());
        assertEquals("https://mcp.example.com/sse", result.url());
    }

    @Test
    void sseRemoteMapsToSseType() {
        var remote = new RegistryApiTypes.Remote("sse", "https://mcp.example.com/sse");
        var result = map(server("com.example/hosted", List.of(), List.of(remote)));
        assertEquals(McpServerType.SSE, result.type());
    }

    @Test
    void unsupportedRuntimeIsSurfacedButNotRunnable() {
        var pkg = new RegistryApiTypes.Package("nuget", null, "Some.Server", "1.0", "dnx",
            new RegistryApiTypes.Transport("stdio", null), List.of(), List.of(), List.of());
        var result = map(server("com.x/dotnet", List.of(pkg), null));
        assertFalse(result.runnable());
        assertEquals("unsupported", result.runtime());
        assertNull(result.command());
        assertFalse(result.notRunnableReason() == null || result.notRunnableReason().isBlank());
    }

    @Test
    void npmIsPreferredOverPypiWhenBothArePresent() {
        var npm = new RegistryApiTypes.Package("pypi", null, "py", null, "uvx",
            new RegistryApiTypes.Transport("stdio", null), List.of(), List.of(), List.of());
        var oci = new RegistryApiTypes.Package("npm", null, "node", null, "npx",
            new RegistryApiTypes.Transport("stdio", null), List.of(), List.of(), List.of());
        var result = map(server("com.x/multi", List.of(npm, oci), null));
        assertEquals("npx", result.runtime());
    }

    @Test
    void displayNameFallsBackToLastPathSegment() {
        var pkg = new RegistryApiTypes.Package("npm", null, "p", null, "npx",
            new RegistryApiTypes.Transport("stdio", null), List.of(), List.of(), List.of());
        var result = map(server("io.github.acme/weather-station", List.of(pkg), null));
        assertEquals("weather-station", result.displayName());
    }

    // Replaces the old verifiedFlagIsPassedThroughInResult test - there is no
    // "verified" concept in the real registry API (see RegistryApiTypes'
    // class javadoc, 2026-08-03 fix); the real, useful signal is lifecycle
    // status, sourced from the sibling _meta envelope, not the Server record.
    @Test
    void statusFlowsThroughFromCallerToResult() {
        var pkg = new RegistryApiTypes.Package("npm", null, "test", null, "npx",
            new RegistryApiTypes.Transport("stdio", null), List.of(), List.of(), List.of());
        var server = server("com.x/some-server", List.of(pkg), null);

        var activeResult = mapper.map(server, "active");
        assertEquals("active", activeResult.status());

        var deprecatedResult = mapper.map(server, "deprecated");
        assertEquals("deprecated", deprecatedResult.status());
    }

    @Test
    void missingOrBlankStatusDefaultsToActive() {
        var pkg = new RegistryApiTypes.Package("npm", null, "test", null, "npx",
            new RegistryApiTypes.Transport("stdio", null), List.of(), List.of(), List.of());
        var server = server("com.x/no-meta-server", List.of(pkg), null);

        assertEquals("active", mapper.map(server, null).status(), "a missing _meta must fail open to active, not hide the result");
        assertEquals("active", mapper.map(server, "").status());
        assertEquals("active", mapper.map(server, "  ").status());
    }
}
