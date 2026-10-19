package com.agenticai.core.agents.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.agenticai.core.agents.tools.McpConnectionDiagnoser.Diagnosis;
import com.agenticai.core.agents.tools.RemoteMcpPreProbe.FailureKind;
import com.agenticai.core.agents.tools.RemoteMcpPreProbe.ProbeResult;
import com.agenticai.core.mcp.McpConnectionPhase;
import com.agenticai.core.mcp.McpServerType;
import java.io.IOException;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.Test;

/**
 * The Connection Doctor's status→hint table (MCP-DOCTOR, 2026-07-25) - each probe/failure shape
 * must map to the documented phase and an actionable hint, because the whole point of this feature
 * is replacing "Client failed to initialize by explicit API call" with something a tenant can act
 * on.
 */
class McpConnectionDiagnoserTest {

    private static ProbeResult status(int code, String contentType) {
        return new ProbeResult(true, code, contentType, FailureKind.NONE, null);
    }

    // --- probe status → hint table ------------------------------------------------

    @Test
    void status401MapsToCredentialsHint() {
        Optional<Diagnosis> d =
                McpConnectionDiagnoser.diagnoseProbe(
                        status(401, null), McpServerType.STREAMABLE_HTTP);
        assertTrue(d.isPresent());
        assertEquals(McpConnectionPhase.HTTP, d.get().phase());
        assertTrue(
                d.get().hint().contains("Authorization = Bearer"),
                "hint should show the env-header fix");
    }

    @Test
    void status403MapsToCredentialsHint() {
        Optional<Diagnosis> d =
                McpConnectionDiagnoser.diagnoseProbe(status(403, null), McpServerType.SSE);
        assertTrue(d.isPresent());
        assertTrue(d.get().hint().contains("credentials"));
    }

    @Test
    void status404MapsToWrongUrlHint() {
        Optional<Diagnosis> d =
                McpConnectionDiagnoser.diagnoseProbe(
                        status(404, null), McpServerType.STREAMABLE_HTTP);
        assertTrue(d.isPresent());
        assertEquals(McpConnectionPhase.HTTP, d.get().phase());
        assertTrue(d.get().hint().contains("URL looks wrong"));
    }

    @Test
    void status405OnAnSseRegistrationSuggestsSwitchingToStreamableHttp() {
        Optional<Diagnosis> d =
                McpConnectionDiagnoser.diagnoseProbe(status(405, null), McpServerType.SSE);
        assertTrue(d.isPresent());
        assertTrue(d.get().hint().contains("STREAMABLE_HTTP"));
    }

    @Test
    void status500MapsToServerSideErrorHint() {
        Optional<Diagnosis> d =
                McpConnectionDiagnoser.diagnoseProbe(
                        status(503, null), McpServerType.STREAMABLE_HTTP);
        assertTrue(d.isPresent());
        assertTrue(d.get().detail().contains("503"));
    }

    @Test
    void eventStreamAnswerOnAStreamableHttpRegistrationSuggestsSwitchingToSse() {
        Optional<Diagnosis> d =
                McpConnectionDiagnoser.diagnoseProbe(
                        status(200, "text/event-stream; charset=utf-8"),
                        McpServerType.STREAMABLE_HTTP);
        assertTrue(d.isPresent());
        assertTrue(d.get().hint().contains("switching the registration's type to SSE"));
    }

    @Test
    void plainOkProbeYieldsNoDiagnosisOfItsOwn() {
        assertTrue(
                McpConnectionDiagnoser.diagnoseProbe(
                                status(200, "application/json"), McpServerType.STREAMABLE_HTTP)
                        .isEmpty());
        assertTrue(
                McpConnectionDiagnoser.diagnoseProbe(ProbeResult.skipped(), McpServerType.SSE)
                        .isEmpty());
        assertTrue(McpConnectionDiagnoser.diagnoseProbe(null, McpServerType.SSE).isEmpty());
    }

    @Test
    void unreachableAndTimeoutAndDnsProbesMapToUnreachableHints() {
        Diagnosis refused =
                McpConnectionDiagnoser.diagnoseProbe(
                                new ProbeResult(
                                        true, null, null, FailureKind.CONNECT_REFUSED, "refused"),
                                McpServerType.SSE)
                        .orElseThrow();
        assertEquals(McpConnectionPhase.HTTP, refused.phase());
        assertTrue(refused.detail().contains("unreachable"));

        Diagnosis timeout =
                McpConnectionDiagnoser.diagnoseProbe(
                                new ProbeResult(true, null, null, FailureKind.TIMEOUT, "slow"),
                                McpServerType.SSE)
                        .orElseThrow();
        assertTrue(timeout.hint().contains("unreachable"));

        Diagnosis dns =
                McpConnectionDiagnoser.diagnoseProbe(
                                new ProbeResult(true, null, null, FailureKind.UNKNOWN_HOST, "nx"),
                                McpServerType.SSE)
                        .orElseThrow();
        assertTrue(dns.detail().contains("does not resolve"));
    }

    // --- remote failure: probe evidence wins, then failure shape -------------------

    @Test
    void remoteFailureWithProbeEvidenceUsesTheProbeDiagnosis() {
        Diagnosis d =
                McpConnectionDiagnoser.diagnoseRemoteFailure(
                        new RuntimeException("Client failed to initialize by explicit API call"),
                        status(401, null),
                        McpServerType.STREAMABLE_HTTP);
        assertEquals(McpConnectionPhase.HTTP, d.phase());
        assertTrue(d.hint().contains("Authorization"));
    }

    @Test
    void remoteInitializeTimeoutWithoutProbeEvidenceMapsToInitTimeoutWithTypeSwitchHint() {
        // The exact live failure that motivated this feature: "Android TV"
        // STREAMABLE_HTTP remote, 38 tools, opaque Reactor-wrapped timeout.
        Diagnosis d =
                McpConnectionDiagnoser.diagnoseRemoteFailure(
                        new RuntimeException("Client failed to initialize by explicit API call"),
                        status(200, "application/json"),
                        McpServerType.STREAMABLE_HTTP);
        assertEquals(McpConnectionPhase.INIT_TIMEOUT, d.phase());
        assertNotNull(d.hint());
        assertTrue(d.hint().contains("SSE"), "hint should mention the transport-type switch");
    }

    @Test
    void remoteRealTimeoutExceptionInTheCauseChainIsDetected() {
        RuntimeException wrapped =
                new RuntimeException("boom", new RuntimeException(new TimeoutException("60s")));
        Diagnosis d =
                McpConnectionDiagnoser.diagnoseRemoteFailure(
                        wrapped, ProbeResult.skipped(), McpServerType.SSE);
        assertEquals(McpConnectionPhase.INIT_TIMEOUT, d.phase());
    }

    @Test
    void remoteNonTimeoutFailureMapsToHandshake() {
        Diagnosis d =
                McpConnectionDiagnoser.diagnoseRemoteFailure(
                        new IllegalStateException("unexpected message shape"),
                        ProbeResult.skipped(),
                        McpServerType.SSE);
        assertEquals(McpConnectionPhase.HANDSHAKE, d.phase());
    }

    // --- stdio failures -----------------------------------------------------------

    @Test
    void stdioCannotRunProgramMapsToSpawnPhase() {
        RuntimeException sdkShape =
                new RuntimeException(
                        "[npx.cmd, -y, some-pkg]",
                        new IOException(
                                "Cannot run program \"npx.cmd\": CreateProcess error=2, The system cannot find the file specified"));
        Diagnosis d = McpConnectionDiagnoser.diagnoseStdioFailure(sdkShape, List.of());
        assertEquals(McpConnectionPhase.SPAWN, d.phase());
        assertTrue(d.hint().contains("command"));
    }

    @Test
    void stdioTimeoutWithStderrTailSurfacesTheStderrLines() {
        // The live case: wrong binary name, actionable line sitting in stderr.
        List<String> stderr =
                List.of(
                        "Error: unknown entrypoint",
                        "Use `uvx --from dee-mcp dee-mcp-server` instead");
        Diagnosis d =
                McpConnectionDiagnoser.diagnoseStdioFailure(
                        new RuntimeException("Client failed to initialize by explicit API call"),
                        stderr);
        assertEquals(McpConnectionPhase.INIT_TIMEOUT, d.phase());
        assertTrue(
                d.detail().contains("Use `uvx --from dee-mcp dee-mcp-server` instead"),
                "the actionable stderr line must reach the persisted detail");
        assertTrue(d.hint().contains("stderr"));
    }

    @Test
    void stdioGenericFailureWithoutStderrMapsToHandshakeWithNoFabricatedHint() {
        Diagnosis d =
                McpConnectionDiagnoser.diagnoseStdioFailure(
                        new IllegalStateException("proto error"), List.of());
        assertEquals(McpConnectionPhase.HANDSHAKE, d.phase());
        assertEquals(null, d.hint());
    }

    // --- MCP-STDIO-SANDBOXING: named missing env var(s) on a timeout ------------

    @Test
    void stdioTimeoutWithOneKnownMissingEnvVarNamesItSpecificallyInsteadOfTheGenericHint() {
        Diagnosis d =
                McpConnectionDiagnoser.diagnoseStdioFailure(
                        new RuntimeException("Client failed to initialize by explicit API call"),
                        List.of(),
                        List.of("BRAVE_API_KEY"));

        assertEquals(McpConnectionPhase.INIT_TIMEOUT, d.phase());
        assertTrue(
                d.hint().contains("BRAVE_API_KEY"),
                "hint should name the specific missing variable");
        assertTrue(d.hint().contains("hasn't been configured"));
        assertFalse(
                d.hint().contains("may be waiting for missing configuration"),
                "the old generic hint text should be replaced, not merely supplemented");
    }

    @Test
    void stdioTimeoutWithMultipleMissingEnvVarsListsThemAllAndUsesThePluralForm() {
        Diagnosis d =
                McpConnectionDiagnoser.diagnoseStdioFailure(
                        new RuntimeException("Client failed to initialize by explicit API call"),
                        List.of(),
                        List.of("SLACK_BOT_TOKEN", "SLACK_TEAM_ID"));

        assertTrue(d.hint().contains("SLACK_BOT_TOKEN"));
        assertTrue(d.hint().contains("SLACK_TEAM_ID"));
        assertTrue(
                d.hint().contains("haven't been configured"),
                "plural form for more than one missing var");
    }

    @Test
    void stdioTimeoutWithNoKnownMissingVarsFallsBackToTheGenericHint() {
        // No provenance to check against (e.g. a hand-typed custom command) -
        // must not fabricate a variable name.
        Diagnosis d =
                McpConnectionDiagnoser.diagnoseStdioFailure(
                        new RuntimeException("Client failed to initialize by explicit API call"),
                        List.of(),
                        List.of());

        assertTrue(d.hint().contains("may be waiting for missing configuration"));
    }

    @Test
    void stdioTimeoutWithMissingVarsAndStderrCombinesBothInsteadOfDroppingEither() {
        List<String> stderr = List.of("connecting...");
        Diagnosis d =
                McpConnectionDiagnoser.diagnoseStdioFailure(
                        new RuntimeException("Client failed to initialize by explicit API call"),
                        stderr,
                        List.of("GITHUB_PERSONAL_ACCESS_TOKEN"));

        assertTrue(d.hint().contains("GITHUB_PERSONAL_ACCESS_TOKEN"));
        assertTrue(
                d.detail().contains("connecting..."),
                "stderr must still reach detail even when the hint is the named-var one");
    }

    @Test
    void twoArgOverloadStillWorksAndBehavesAsThoughNoMissingVarsAreKnown() {
        // Compatibility overload every pre-existing caller (including every
        // other test in this class) uses - must not regress to a different
        // phase/hint just because the 3-arg overload now exists.
        Diagnosis d =
                McpConnectionDiagnoser.diagnoseStdioFailure(
                        new RuntimeException("Client failed to initialize by explicit API call"),
                        List.of());

        assertEquals(McpConnectionPhase.INIT_TIMEOUT, d.phase());
        assertTrue(d.hint().contains("may be waiting for missing configuration"));
    }
}
