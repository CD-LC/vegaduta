package com.agenticai.core.agents.tools.browseruse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.agenticai.core.agents.tools.browseruse.BrowserAutomationDtos.Capabilities;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

/** The capabilities answer says why the browser is missing, so the console can offer the add-on. */
class BrowserAutomationControllerTest {

    @SuppressWarnings("unchecked")
    private static BrowserAutomationController controller(BrowserUseSessionManager manager) {
        ObjectProvider<BrowserUseSessionManager> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(manager);
        return new BrowserAutomationController(true, false, false, provider);
    }

    @Test
    void autoModeWithoutAChromiumReportsUnavailableWithTheAddOnHint() {
        BrowserUseSessionManager manager =
                new BrowserUseSessionManager(
                        "auto", "/nonexistent/chrome", 2, 10, 300, 1800, 20_000, 15_000);

        Capabilities caps = controller(manager).capabilities();

        assertThat(caps.browserAvailable()).isFalse();
        assertThat(caps.browserUnavailableReason()).contains("browser add-on");
        assertThat(caps.profilesEnabled()).isTrue();
    }

    @Test
    void switchedOffSaysSoAndAbsentManagerHasAReason() {
        BrowserUseSessionManager off =
                new BrowserUseSessionManager("false", "", 2, 10, 300, 1800, 20_000, 15_000);

        assertThat(controller(off).capabilities().browserUnavailableReason())
                .contains("switched off");
        assertThat(controller(null).capabilities().browserUnavailableReason()).isNotBlank();
    }

    @Test
    void anAvailableBrowserHasNoReason() {
        BrowserUseSessionManager on =
                new BrowserUseSessionManager("true", "", 2, 10, 300, 1800, 20_000, 15_000);

        Capabilities caps = controller(on).capabilities();

        assertThat(caps.browserAvailable()).isTrue();
        assertThat(caps.browserUnavailableReason()).isNull();
    }

    @Test
    void autoModeWithARemoteBrowserIsEnabledWithoutAnyLocalChromium() {
        BrowserUseSessionManager manager =
                new BrowserUseSessionManager(
                        "auto", "/nonexistent/chrome", 2, 10, 300, 1800, 20_000, 15_000, "ws://127.0.0.1:1/");

        assertThat(manager.isEnabled()).isTrue();
    }

    @Test
    void aRemoteBrowserThatDoesNotAnswerIsReportedUnavailableWithItsAddress() {
        // Port 1 on loopback refuses the connection at once.
        BrowserUseSessionManager manager =
                new BrowserUseSessionManager(
                        "auto", "/nonexistent/chrome", 2, 10, 300, 1800, 20_000, 15_000, "ws://127.0.0.1:1/");

        Capabilities caps = controller(manager).capabilities();

        assertThat(caps.browserAvailable()).isFalse();
        assertThat(caps.browserUnavailableReason()).contains("not answering").contains("ws://127.0.0.1:1/");
    }

    @Test
    void aRemoteBrowserThatAnswersIsAvailable() throws Exception {
        try (java.net.ServerSocket listening = new java.net.ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress())) {
            BrowserUseSessionManager manager =
                    new BrowserUseSessionManager(
                            "auto",
                            "/nonexistent/chrome",
                            2,
                            10,
                            300,
                            1800,
                            20_000,
                            15_000,
                            "ws://127.0.0.1:" + listening.getLocalPort() + "/");

            Capabilities caps = controller(manager).capabilities();

            assertThat(caps.browserAvailable()).isTrue();
            assertThat(caps.browserUnavailableReason()).isNull();
        }
    }
}
