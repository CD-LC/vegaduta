package com.agenticai.core.agents.tools.browseruse;

import static com.agenticai.core.agents.tools.browseruse.BrowserTakeoverFixtures.AGENT;
import static com.agenticai.core.agents.tools.browseruse.BrowserTakeoverFixtures.CHAT_SESSION;
import static com.agenticai.core.agents.tools.browseruse.BrowserTakeoverFixtures.TENANT;
import static com.agenticai.core.agents.tools.browseruse.BrowserTakeoverFixtures.USER;
import static com.agenticai.core.agents.tools.browseruse.BrowserTakeoverFixtures.assertStatus;
import static com.agenticai.core.agents.tools.browseruse.BrowserTakeoverFixtures.click;
import static com.agenticai.core.agents.tools.browseruse.BrowserTakeoverFixtures.id;
import static com.agenticai.core.agents.tools.browseruse.BrowserTakeoverFixtures.key;
import static com.agenticai.core.agents.tools.browseruse.BrowserTakeoverFixtures.rig;
import static com.agenticai.core.agents.tools.browseruse.BrowserTakeoverFixtures.takeControl;
import static com.agenticai.core.agents.tools.browseruse.BrowserTakeoverFixtures.type;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

import com.agenticai.core.agents.tools.browseruse.BrowserTakeoverDtos.Begin;
import com.agenticai.core.agents.tools.browseruse.BrowserTakeoverDtos.InputEvent;
import com.agenticai.core.agents.tools.browseruse.BrowserTakeoverDtos.View;
import com.agenticai.core.agents.tools.browseruse.BrowserTakeoverFixtures.Rig;
import com.agenticai.core.audit.AuditOutcome;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * Human takeover, continued from {@link BrowserTakeoverServiceTest}: how the person's input reaches
 * the fake page (mapping, validation, clamping, rate limit, generic failures), the site rule, and
 * the live stream (slots, epoch binding, audit).
 */
class BrowserTakeoverInputAndStreamTest {

    private static final String PASSWORD = "hunter2-Pw";

    // ---- input relay
    // --------------------------------------------------------------------------------------

    @Test
    void clicksAreMappedFromFramePixelsToPageCoordinatesAndClamped() {
        assertThat(BrowserTakeoverService.mapCoordinate(512, 1024, 1280)).isEqualTo(640);
        assertThat(BrowserTakeoverService.mapCoordinate(0, 1024, 1280)).isZero();
        assertThat(BrowserTakeoverService.mapCoordinate(1024, 1024, 1280))
                .as("far edge stays inside the page")
                .isEqualTo(1279);
        assertThat(BrowserTakeoverService.mapCoordinate(5, 0, 1280)).isZero();

        Rig rig = rig();
        Begin begin = takeControl(rig);
        rig.driver.viewport = new int[] {1280, 800};
        rig.service.input(TENANT, USER, id(begin), begin.token(), click(256, 160, 1024, 640));
        assertThat(rig.driver.calls).containsExactly("click 320,200");
    }

    @Test
    void malformedOrOversizedInputIsRefusedBeforeItReachesThePage() {
        Rig rig = rig();
        Begin begin = takeControl(rig);
        UUID id = id(begin);
        String t = begin.token();
        for (InputEvent bad :
                new InputEvent[] {
                    click(-1, 5, 100, 100),
                    click(5, 101, 100, 100),
                    click(5, 5, 0, 100),
                    click(5, 5, 100, 99_999),
                    new InputEvent("click", null, 5, 100, 100, null, null, null, null),
                    new InputEvent("teleport", null, null, null, null, null, null, null, null),
                    new InputEvent(null, null, null, null, null, null, null, null, null),
                    key(""),
                    key("Control+Alt+Delete"),
                    key("F13"),
                    key("\u0007"),
                    type(""),
                    type("a".repeat(257)),
                    type("bell\u0007"),
                }) {
            assertStatus(() -> rig.service.input(TENANT, USER, id, t, bad), HttpStatus.BAD_REQUEST);
        }
        assertThat(rig.driver.calls).isEmpty();
        rig.service.input(TENANT, USER, id, t, type("a".repeat(256)));
        rig.service.input(TENANT, USER, id, t, key("Enter"));
        rig.service.input(TENANT, USER, id, t, key("x"));
        assertThat(rig.driver.calls).containsExactly("type len=256", "press Enter", "press x");
    }

    @Test
    void scrollIsClampedToTheConfiguredCap() {
        Rig rig = rig();
        Begin begin = takeControl(rig);
        rig.service.input(
                TENANT,
                USER,
                id(begin),
                begin.token(),
                new InputEvent("scroll", 10, 10, null, null, null, null, 99_999, -99_999));
        assertThat(rig.driver.calls).containsExactly("scroll 2000,-2000");
    }

    @Test
    void inputIsRateLimitedPerSessionAndOnlyAcceptedEventsResetTheIdleClock() {
        Rig rig = rig();
        Begin begin = takeControl(rig);
        UUID id = id(begin);
        int accepted = 0;
        for (int i = 0; i < 100; i++) {
            try {
                rig.service.input(TENANT, USER, id, begin.token(), key("Tab"));
                accepted++;
            } catch (ResponseStatusException e) {
                assertThat(e.getStatusCode().value()).isEqualTo(429);
            }
        }
        assertThat(accepted).as("burst of 40 at one instant").isEqualTo(40);
        assertThat(rig.driver.calls).hasSize(40);
        rig.advance(1);
        rig.service.input(TENANT, USER, id, begin.token(), key("Tab")); // 20/s refilled
        assertThat(rig.driver.calls).hasSize(41);
    }

    @Test
    void aFailingPageAnswersGenericallyAndEchoesNothing() {
        Rig rig = rig();
        Begin begin = takeControl(rig);
        BrowserTakeoverFixtures.FakeDriver failing =
                new BrowserTakeoverFixtures.FakeDriver() {
                    @Override
                    public void typeText(String sessionKey, String text) {
                        throw new BrowserUseException("selector #pw failed for value " + text);
                    }
                };
        rig.service.setDriver(failing);
        assertThatThrownBy(
                        () ->
                                rig.service.input(
                                        TENANT, USER, id(begin), begin.token(), type(PASSWORD)))
                .isInstanceOfSatisfying(
                        ResponseStatusException.class,
                        e -> {
                            assertThat(e.getStatusCode().value()).isEqualTo(409);
                            assertThat(String.valueOf(e.getReason()))
                                    .doesNotContain(PASSWORD)
                                    .doesNotContain("selector");
                        });
    }

    // ---- site rule
    // ----------------------------------------------------------------------------------------

    @Test
    void takeoverIsRefusedOnASiteTheAllowListOrProfileDoesNotCover() {
        Rig rig = rig();
        rig.driver.refusal =
                "takeover is only offered on sites an administrator has allow-listed for this agent";
        assertThatThrownBy(() -> rig.service.agentRequest(rig.key, "captcha", "x"))
                .isInstanceOf(BrowserUseException.class)
                .hasMessageContaining("allow-listed");
        assertThat(rig.service.controlFor(rig.key).state())
                .as("the agent keeps control")
                .isEqualTo(BrowserTakeoverState.AGENT_CONTROL);
        assertStatus(
                () -> rig.service.pause(TENANT, USER, AGENT, CHAT_SESSION, "o", "r"),
                HttpStatus.FORBIDDEN);
        assertThat(rig.service.controlFor(rig.key).state())
                .isEqualTo(BrowserTakeoverState.AGENT_CONTROL);
    }

    // ---- stream
    // -------------------------------------------------------------------------------------------

    @Test
    void streamSlotsAreCappedAndReleased() {
        Rig rig =
                new Rig(
                        new BrowserTakeoverSettings(
                                true,
                                BrowserTakeoverFixtures.provider(BrowserTakeoverFixtures.v2(true)),
                                2,
                                1024,
                                55,
                                300,
                                "return",
                                900,
                                600,
                                120,
                                20,
                                40,
                                256,
                                2000,
                                1,
                                false,
                                "test-secret",
                                1500,
                                200));
        Begin begin = takeControl(rig);
        rig.service.openStream(TENANT, USER, id(begin), begin.token());
        assertStatus(
                () -> rig.service.openStream(TENANT, USER, id(begin), begin.token()),
                HttpStatus.TOO_MANY_REQUESTS);
        rig.service.releaseStream();
        rig.service.openStream(TENANT, USER, id(begin), begin.token());
    }

    @Test
    void framesAreBoundedByTheSettingsAndCarryOnlyCountdowns() {
        Rig rig = rig();
        Begin begin = takeControl(rig);
        int epoch = rig.service.openStream(TENANT, USER, id(begin), begin.token());
        BrowserTakeoverDtos.FrameEvent frame =
                rig.service.captureFrame(TENANT, USER, id(begin), epoch);
        assertThat(rig.driver.calls).containsExactly("frame 1024/55");
        assertThat(frame.mime()).isEqualTo("image/jpeg");
        assertThat(frame.maxRemainingSeconds()).isEqualTo(900);
        assertThat(frame.idleRemainingSeconds()).isEqualTo(300);
        rig.advance(100);
        rig.service.captureFrame(TENANT, USER, id(begin), epoch);
        assertThat(rig.service.captureFrame(TENANT, USER, id(begin), epoch).idleRemainingSeconds())
                .as("watching is not activity")
                .isEqualTo(200);
    }

    // ---- audit
    // --------------------------------------------------------------------------------------------

    @Test
    void startAndEndAreAuditedWithIdsAndKindsOnly() {
        Rig rig = rig();
        Begin begin = takeControl(rig);
        rig.service.handBack(TENANT, USER, id(begin));
        ArgumentCaptor<String> action = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> target = ArgumentCaptor.forClass(String.class);
        verify(rig.audit, timeout(3_000).atLeast(3))
                .record(
                        eq(USER),
                        action.capture(),
                        anyString(),
                        target.capture(),
                        eq(AuditOutcome.SUCCESS),
                        anyString());
        assertThat(action.getAllValues())
                .contains(
                        "BROWSER_TAKEOVER_REQUESTED",
                        "BROWSER_TAKEOVER_STARTED",
                        "BROWSER_TAKEOVER_ENDED");
        assertThat(target.getAllValues()).containsOnly(begin.view().takeoverId());
        verify(rig.audit, atLeastOnce())
                .record(
                        eq(USER),
                        eq("BROWSER_TAKEOVER_ENDED"),
                        anyString(),
                        anyString(),
                        eq(AuditOutcome.SUCCESS),
                        org.mockito.ArgumentMatchers.contains("endedBy=user"));
    }
}
