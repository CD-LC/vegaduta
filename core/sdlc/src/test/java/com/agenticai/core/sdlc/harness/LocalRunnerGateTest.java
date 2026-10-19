package com.agenticai.core.sdlc.harness;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.env.MockEnvironment;

/** The second acknowledgement, the loopback-bind rule and the factory wiring around them. */
class LocalRunnerGateTest {

    private static MockEnvironment env(String ack, String address) {
        MockEnvironment e = new MockEnvironment();
        if (ack != null) {
            e.setProperty(LocalRunnerGate.ACK_PROPERTY, ack);
        }
        if (address != null) {
            e.setProperty("server.address", address);
        }
        return e;
    }

    @Test
    void loopbackAddressesAreRecognisedAndEverythingElseIsNot() {
        for (String ok : new String[] {"127.0.0.1", "127.1.2.3", "localhost", "LOCALHOST", "::1", "[::1]", "0:0:0:0:0:0:0:1"}) {
            assertTrue(LocalRunnerGate.isLoopback(ok), ok);
        }
        for (String bad : new String[] {null, "", "  ", "0.0.0.0", "::", "192.168.1.5", "10.0.0.1", "example.com",
                "localhost.evil.com", "127.0.0.1.evil.com", "deadbeef", "abc.def", "128.0.0.1"}) {
            assertFalse(LocalRunnerGate.isLoopback(bad), String.valueOf(bad));
        }
    }

    @Test
    void anUnsetBindMeansAllInterfacesAndIsRefused() {
        String reason = LocalRunnerGate.refusal(env("true", null)).orElseThrow();
        assertTrue(reason.contains("unset"), reason);
    }

    @Test
    void theAcknowledgementIsRequiredAndTrueIsTheOnlyAcceptedValue() {
        assertTrue(LocalRunnerGate.refusal(env(null, "127.0.0.1")).isPresent());
        assertTrue(LocalRunnerGate.refusal(env("false", "127.0.0.1")).isPresent());
        assertTrue(LocalRunnerGate.refusal(env("yes please", "127.0.0.1")).isPresent());
        assertTrue(LocalRunnerGate.refusal(env("true", "127.0.0.1")).isEmpty());
    }

    @Test
    void aNonLoopbackBindIsRefusedEvenWithTheAcknowledgement() {
        assertTrue(LocalRunnerGate.refusal(env("true", "0.0.0.0")).isPresent());
        assertTrue(LocalRunnerGate.refusal(env("true", "10.1.2.3")).isPresent());
    }

    @Test
    void theBannerShoutsAboutTheRisk() {
        String b = LocalRunnerGate.banner();
        assertTrue(b.contains("LOCAL CODING RUNNER ACTIVE") && b.contains("NO sandbox"));
    }

    // ---- through the factory ---------------------------------------------------------------

    private static CodingSandboxFactory factory(MockEnvironment env, boolean allowLocal, Path workspace) {
        return new CodingSandboxFactory(
                null, null, env, true, "local", allowLocal, false, false, "", 100, "mvn", "bash", workspace.toString());
    }

    @Test
    void factoryRefusesWithoutTheSecondAcknowledgement(@TempDir Path ws) {
        CodingSandboxFactory f = factory(env(null, "127.0.0.1"), true, ws);
        assertTrue(f.notReadyReason().contains(LocalRunnerGate.ACK_PROPERTY));
        assertThrows(CodingSandboxUnavailableException.class, () -> f.open(300));
    }

    @Test
    void factoryRefusesOnANonLoopbackBind(@TempDir Path ws) {
        CodingSandboxFactory f = factory(env("true", "0.0.0.0"), true, ws);
        assertTrue(f.notReadyReason().contains("loopback"));
        assertThrows(CodingSandboxUnavailableException.class, () -> f.open(300));
    }

    @Test
    void factoryStillRefusesWhenAllowLocalRunnerIsOff(@TempDir Path ws) {
        CodingSandboxFactory f = factory(env("true", "127.0.0.1"), false, ws);
        assertEquals("The local runner is not allowed on this deployment.", f.notReadyReason());
        assertThrows(CodingSandboxUnavailableException.class, () -> f.open(300));
    }

    @Test
    void factoryStillRefusesUnderADeploymentProfile(@TempDir Path ws) {
        MockEnvironment e = env("true", "127.0.0.1");
        e.setActiveProfiles("security");
        CodingSandboxFactory f = factory(e, true, ws);
        assertTrue(f.notReadyReason().contains("deployment profile"));
    }

    @Test
    void factoryOpensTheLocalSandboxWhenEveryConditionHolds(@TempDir Path ws) {
        CodingSandboxFactory f = factory(env("true", "127.0.0.1"), true, ws);
        assertNull(f.notReadyReason());
        try (CodingSandbox sandbox = f.open(300)) {
            assertTrue(sandbox instanceof LocalCodingSandbox);
        }
    }
}
