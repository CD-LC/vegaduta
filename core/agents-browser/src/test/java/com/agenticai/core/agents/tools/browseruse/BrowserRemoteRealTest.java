package com.agenticai.core.agents.tools.browseruse;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * A real browser, not a mock: drives {@link BrowserUseSessionManager} against a running Playwright
 * server (the personal install's {@code browser} add-on, services/browser-runner) and a real public
 * page. Everything else in this package runs over mocks and says so ("does NOT prove that real
 * Chromium renders").
 *
 * <p>Skipped unless {@code BROWSER_REMOTE_URL} names the server, so a normal build never needs a
 * browser or the internet:
 *
 * <pre>
 *   docker compose -f deploy/compose/personal.yml -f deploy/compose/browser.yml --profile browser up -d browser-runner
 *   # the runner publishes no port; reach it from the host through a throwaway forwarder:
 *   docker run -d --rm --name browser-fwd --network &lt;project&gt;_browser-net -p 127.0.0.1:13000:3000 \
 *       alpine/socat tcp-listen:3000,fork,reuseaddr tcp:browser-runner:3000
 *   BROWSER_REMOTE_URL=ws://127.0.0.1:13000/ mvn -pl agents-browser test -Dtest=BrowserRemoteRealTest
 * </pre>
 *
 * The Playwright client of this build must match the server's version (1.49.x).
 */
@EnabledIfEnvironmentVariable(named = "BROWSER_REMOTE_URL", matches = "ws.+")
class BrowserRemoteRealTest {

    private static final String TENANT = "t_real";
    private final UUID agent = UUID.randomUUID();
    private final String session = "real-" + UUID.randomUUID();
    private BrowserUseSessionManager manager;

    @BeforeEach
    void setUp() {
        manager =
                new BrowserUseSessionManager(
                        "auto",
                        "",
                        2,
                        10,
                        300,
                        1800,
                        30_000,
                        20_000,
                        System.getenv("BROWSER_REMOTE_URL"));
    }

    @AfterEach
    void tearDown() {
        manager.shutdown();
    }

    @Test
    void theAddOnIsReportedAsReachable() {
        assertThat(manager.isEnabled()).isTrue();
        assertThat(manager.remoteProblem()).isNull();
    }

    @Test
    void navigatesReadsScreenshotsAndClicksARealPage() {
        BrowserUseSessionManager.NavigateResult opened =
                manager.navigate(TENANT, agent, session, "https://example.com");
        assertThat(opened.title()).isEqualTo("Example Domain");
        assertThat(opened.finalUrl()).startsWith("https://example.com");

        assertThat(manager.readPageText(TENANT, agent, session))
                .contains("documentation examples");

        byte[] frame = manager.screenshot(TENANT, agent, session);
        assertThat(frame.length).isGreaterThan(2_000);
        // JPEG start-of-image marker: a real rendered frame (the manager captures JPEG).
        assertThat(frame[0] & 0xFF).isEqualTo(0xFF);
        assertThat(frame[1] & 0xFF).isEqualTo(0xD8);

        // example.com has no link, so clicking needs a page that does: a public site built for browser tests.
        BrowserUseSessionManager.NavigateResult index =
                manager.navigate(TENANT, agent, session, "https://the-internet.herokuapp.com/");
        assertThat(index.title()).isEqualTo("The Internet");
        manager.click(TENANT, agent, session, "Checkboxes");
        assertThat(manager.readPageText(TENANT, agent, session)).contains("Checkboxes");
    }

    @Test
    void aPrivateAddressIsStillRefusedBeforeTheRemoteBrowserSeesIt() {
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> manager.navigate(TENANT, agent, session, "http://127.0.0.1:8080/"))
                .isInstanceOf(BrowserUseException.class);
    }
}
