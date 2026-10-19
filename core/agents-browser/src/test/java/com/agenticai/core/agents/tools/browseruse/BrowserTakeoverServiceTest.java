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

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.agenticai.core.agents.tools.browseruse.BrowserTakeoverDtos.Begin;
import com.agenticai.core.agents.tools.browseruse.BrowserTakeoverDtos.InputEvent;
import com.agenticai.core.agents.tools.browseruse.BrowserTakeoverDtos.View;
import com.agenticai.core.agents.tools.browseruse.BrowserTakeoverFixtures.Rig;
import com.agenticai.core.audit.AuditOutcome;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * The rules of human takeover, against a fake page: who may take control, what the agent is refused
 * meanwhile, the token, rate and size limits, the idle / max-duration / wait clocks, the site rule,
 * the audit trail and - above all - that nothing the person types ever reaches anything the agent
 * can read, a log line or an audit entry. The input relay, the site rule and the live stream are
 * in {@link BrowserTakeoverInputAndStreamTest}; the shared helpers in {@link
 * BrowserTakeoverFixtures}.
 */
class BrowserTakeoverServiceTest {

    private static final String PASSWORD = "hunter2-Pw";
    private static final String CODE = "9f3k7q";

    private ListAppender<ILoggingEvent> logs;
    private Logger takeoverLogger;

    @BeforeEach
    void captureLogs() {
        takeoverLogger =
                (Logger) LoggerFactory.getLogger("com.agenticai.core.agents.tools.browseruse");
        takeoverLogger.setLevel(Level.TRACE);
        logs = new ListAppender<>();
        logs.start();
        takeoverLogger.addAppender(logs);
    }

    @AfterEach
    void releaseLogs() {
        takeoverLogger.detachAppender(logs);
    }

    // ---- flag off
    // -----------------------------------------------------------------------------------------

    @Test
    void withTakeoverOffNothingIsRegisteredGatedOrAnswered() {
        for (var settings :
                List.of(
                        BrowserTakeoverFixtures.settings(false, true, "return", false),
                        BrowserTakeoverFixtures.settings(true, false, "return", false))) {
            Rig rig = new Rig(settings);
            assertThat(rig.service.isActive()).isFalse();
            assertThat(rig.service.controlFor(rig.key))
                    .as("no per-session state is allocated")
                    .isNull();
            rig.service.requireAgentControl(rig.key); // never throws
            assertThat(rig.service.holdsSession(rig.key)).isFalse();
            assertThatThrownBy(() -> rig.service.agentRequest(rig.key, "captcha", "x"))
                    .isInstanceOf(BrowserUseException.class);
            assertStatus(
                    () -> rig.service.status(TENANT, USER, AGENT, CHAT_SESSION),
                    HttpStatus.NOT_FOUND);
            assertStatus(
                    () -> rig.service.pause(TENANT, USER, AGENT, CHAT_SESSION, "other", "x"),
                    HttpStatus.NOT_FOUND);
            assertStatus(
                    () -> rig.service.begin(TENANT, USER, UUID.randomUUID()), HttpStatus.NOT_FOUND);
            assertStatus(
                    () ->
                            rig.service.input(
                                    TENANT, USER, UUID.randomUUID(), "t", click(1, 1, 10, 10)),
                    HttpStatus.NOT_FOUND);
            assertStatus(
                    () -> rig.service.handBack(TENANT, USER, UUID.randomUUID()),
                    HttpStatus.NOT_FOUND);
            assertThat(rig.driver.calls).isEmpty();
        }
    }

    // ---- agent request + gate
    // -----------------------------------------------------------------------------

    @Test
    void anAgentRequestParksTheAgentAndTheUserSeesWhatItAskedFor() {
        Rig rig = rig();
        BrowserTakeoverService.AgentOutcome outcome =
                rig.service.agentRequest(
                        rig.key, "captcha", "  Solve\tthe\ncaptcha  \u0007 please ");
        assertThat(outcome.state()).isEqualTo("PAUSED_FOR_USER");
        assertThat(outcome.message()).contains("Do NOT call any other browser tool");
        View view = rig.service.status(TENANT, USER, AGENT, CHAT_SESSION);
        assertThat(view.state()).isEqualTo("PAUSED_FOR_USER");
        assertThat(view.kind()).isEqualTo("captcha");
        assertThat(view.reason()).isEqualTo("Solve the captcha please");
        assertThat(view.host()).as("host only - never path or query").isEqualTo("shop.example");
        assertThat(view.requestedBy()).isEqualTo("agent");
        // asking again is idempotent
        assertThat(rig.service.agentRequest(rig.key, "login", "again").state())
                .isEqualTo("PAUSED_FOR_USER");
        assertThat(rig.service.status(TENANT, USER, AGENT, CHAT_SESSION).kind())
                .isEqualTo("captcha");
    }

    @Test
    void anUnknownKindBecomesOtherAndALongReasonIsCapped() {
        Rig rig = rig();
        rig.service.agentRequest(rig.key, "make-me-admin", "x".repeat(5_000));
        View view = rig.service.status(TENANT, USER, AGENT, CHAT_SESSION);
        assertThat(view.kind()).isEqualTo("other");
        assertThat(view.reason()).hasSize(200);
    }

    @Test
    void everyAgentActionIsRefusedWhileThePageIsPausedOrTheUserDrives() {
        Rig rig = rig();
        rig.service.requireAgentControl(rig.key); // agent in control: fine
        rig.service.agentRequest(rig.key, "login", "Log in");
        assertThatThrownBy(() -> rig.service.requireAgentControl(rig.key))
                .isInstanceOf(BrowserUseException.class)
                .hasMessageContaining("paused for the user");
        Begin begin =
                rig.service.begin(
                        TENANT,
                        USER,
                        UUID.fromString(
                                rig.service
                                        .status(TENANT, USER, AGENT, CHAT_SESSION)
                                        .takeoverId()));
        assertThat(begin.view().state()).isEqualTo("USER_CONTROL");
        assertThatThrownBy(() -> rig.service.requireAgentControl(rig.key))
                .isInstanceOf(BrowserUseException.class)
                .hasMessageContaining("currently has control");
        assertThat(rig.service.holdsSession(rig.key)).isTrue();
        rig.service.handBack(TENANT, USER, id(begin));
        rig.service.requireAgentControl(rig.key); // and back again
        assertThat(rig.service.holdsSession(rig.key)).isFalse();
    }

    @Test
    void theUserCanPauseASessionTheAgentNeverAskedAbout() {
        Rig rig = rig();
        View view = rig.service.pause(TENANT, USER, AGENT, CHAT_SESSION, "other", "I want to look");
        assertThat(view.state()).isEqualTo("PAUSED_FOR_USER");
        assertThat(view.requestedBy()).isEqualTo("user");
        assertThatThrownBy(() -> rig.service.requireAgentControl(rig.key))
                .isInstanceOf(BrowserUseException.class);
    }

    @Test
    void anUnattendedSessionHasNoOneToHandControlTo() {
        Rig rig = rig();
        String other = BrowserUseSessionManager.sessionKey(TENANT, AGENT, "job:1");
        rig.service.onSessionCreated(other, TENANT, null, AGENT);
        assertThatThrownBy(() -> rig.service.agentRequest(other, "captcha", "x"))
                .isInstanceOf(BrowserUseException.class)
                .hasMessageContaining("nobody to hand control to");
        assertThat(rig.service.controlFor(other).state())
                .isEqualTo(BrowserTakeoverState.AGENT_CONTROL);
    }

    @Test
    void requestingWithNoBrowserSessionYetSaysToNavigateFirst() {
        Rig rig = rig();
        assertThatThrownBy(
                        () ->
                                rig.service.agentRequest(
                                        "tenant-1|" + AGENT + "|other-chat", "login", "x"))
                .isInstanceOf(BrowserUseException.class)
                .hasMessageContaining("browser_navigate");
    }

    // ---- authorisation
    // ------------------------------------------------------------------------------------

    @Test
    void anotherUserOrAnotherTenantCannotSeeTakeOverOrDriveSomeoneElsesSession() {
        Rig rig = rig();
        Begin begin = takeControl(rig);
        UUID id = id(begin);
        for (String[] stranger :
                new String[][] {{TENANT, "user-2"}, {"tenant-2", USER}, {"tenant-2", "user-2"}}) {
            assertThat(rig.service.status(stranger[0], stranger[1], AGENT, CHAT_SESSION))
                    .as("status leaks nothing")
                    .isNull();
            assertStatus(
                    () ->
                            rig.service.pause(
                                    stranger[0], stranger[1], AGENT, CHAT_SESSION, "o", "r"),
                    HttpStatus.NOT_FOUND);
            assertStatus(
                    () -> rig.service.begin(stranger[0], stranger[1], id), HttpStatus.NOT_FOUND);
            assertStatus(
                    () -> rig.service.refreshToken(stranger[0], stranger[1], id),
                    HttpStatus.NOT_FOUND);
            // even holding the OWNER's perfectly valid token
            assertStatus(
                    () ->
                            rig.service.input(
                                    stranger[0],
                                    stranger[1],
                                    id,
                                    begin.token(),
                                    click(1, 1, 10, 10)),
                    HttpStatus.NOT_FOUND);
            assertStatus(
                    () -> rig.service.openStream(stranger[0], stranger[1], id, begin.token()),
                    HttpStatus.NOT_FOUND);
            assertStatus(
                    () -> rig.service.handBack(stranger[0], stranger[1], id), HttpStatus.NOT_FOUND);
            assertStatus(
                    () -> rig.service.cancel(stranger[0], stranger[1], id), HttpStatus.NOT_FOUND);
        }
        assertThat(rig.driver.calls).as("nothing reached the page").isEmpty();
        assertThat(rig.service.controlFor(rig.key).state())
                .isEqualTo(BrowserTakeoverState.USER_CONTROL);
    }

    @Test
    void aTokenMintedForAnotherTakeoverUserTenantOrEpochIsRejected() {
        Rig rig = rig();
        Begin begin = takeControl(rig);
        UUID id = id(begin);

        BrowserTakeoverToken forger = new BrowserTakeoverToken("test-secret");
        java.time.Instant exp = BrowserTakeoverFixtures.T0.plusSeconds(60);
        String otherUser = forger.issue(id, 1, TENANT, "user-2", exp);
        String otherTenant = forger.issue(id, 1, "tenant-2", USER, exp);
        String otherTakeover = forger.issue(UUID.randomUUID(), 1, TENANT, USER, exp);
        String oldEpoch = forger.issue(id, 0, TENANT, USER, exp);
        String wrongKey =
                new BrowserTakeoverToken("another-secret").issue(id, 1, TENANT, USER, exp);
        for (String bad :
                new String[] {
                    otherUser, otherTenant, otherTakeover, oldEpoch, wrongKey, "", "garbage", null
                }) {
            assertStatus(
                    () -> rig.service.input(TENANT, USER, id, bad, click(1, 1, 10, 10)),
                    HttpStatus.FORBIDDEN);
            assertStatus(() -> rig.service.openStream(TENANT, USER, id, bad), HttpStatus.FORBIDDEN);
        }
        assertThat(rig.driver.calls).isEmpty();
        // the real one works
        rig.service.input(TENANT, USER, id, begin.token(), click(512, 320, 1024, 640));
        assertThat(rig.driver.calls).containsExactly("click 640,400");
    }

    @Test
    void aTokenFromAnEarlierEpisodeDiesWhenANewEpisodeBegins() {
        Rig rig = rig();
        Begin first = takeControl(rig);
        rig.service.handBack(TENANT, USER, id(first));
        rig.service.pause(TENANT, USER, AGENT, CHAT_SESSION, "login", "again");
        View waiting = rig.service.status(TENANT, USER, AGENT, CHAT_SESSION);
        Begin second = rig.service.begin(TENANT, USER, UUID.fromString(waiting.takeoverId()));
        assertStatus(
                () ->
                        rig.service.input(
                                TENANT, USER, id(second), first.token(), click(1, 1, 10, 10)),
                HttpStatus.FORBIDDEN);
        rig.service.input(TENANT, USER, id(second), second.token(), click(1, 1, 10, 10));
    }

    @Test
    void theTokenExpiresAndCanBeRenewedByItsOwner() {
        Rig rig = rig();
        Begin begin = takeControl(rig);
        UUID id = id(begin);
        assertThat(begin.tokenExpiresInSeconds()).isEqualTo(120);
        rig.advance(121);
        assertStatus(
                () -> rig.service.input(TENANT, USER, id, begin.token(), click(1, 1, 10, 10)),
                HttpStatus.FORBIDDEN);
        BrowserTakeoverDtos.TokenRefresh fresh = rig.service.refreshToken(TENANT, USER, id);
        rig.service.input(TENANT, USER, id, fresh.token(), click(1, 1, 10, 10));
    }

    @Test
    void inputBeforeTakingControlOrAfterHandingBackIsRefused() {
        Rig rig = rig();
        rig.service.agentRequest(rig.key, "captcha", "x");
        UUID id =
                UUID.fromString(rig.service.status(TENANT, USER, AGENT, CHAT_SESSION).takeoverId());
        assertStatus(() -> rig.service.refreshToken(TENANT, USER, id), HttpStatus.CONFLICT);
        Begin begin = rig.service.begin(TENANT, USER, id);
        rig.service.handBack(TENANT, USER, id);
        assertStatus(
                () -> rig.service.input(TENANT, USER, id, begin.token(), click(1, 1, 10, 10)),
                HttpStatus.CONFLICT);
        assertThat(rig.driver.calls).isEmpty();
    }

    // ---- secrets
    // ------------------------------------------------------------------------------------------

    @Test
    void whatThePersonTypesNeverReachesTheAgentALogLineOrAnAuditEntry() {
        Rig rig = rig();
        Begin begin = takeControl(rig);
        UUID id = id(begin);
        String t = begin.token();
        // a password typed in one go, a 2FA code typed key by key, then submitted
        rig.service.input(TENANT, USER, id, t, click(10, 10, 100, 100));
        rig.service.input(TENANT, USER, id, t, type(PASSWORD));
        rig.service.input(TENANT, USER, id, t, key("Enter"));
        for (char c : CODE.toCharArray()) {
            rig.service.input(TENANT, USER, id, t, key(String.valueOf(c)));
        }
        rig.service.input(TENANT, USER, id, t, key("Enter"));
        // it WAS relayed to the page (that is the point) ...
        assertThat(rig.driver.typed).containsExactly(PASSWORD);

        View ended = rig.service.handBack(TENANT, USER, id);
        assertThat(ended.state()).isEqualTo("AGENT_CONTROL");

        // ... and the page text the driver was asked to read back was scrubbed with exactly those
        // candidates
        assertThat(rig.driver.resumeCandidates).containsExactly(PASSWORD, CODE);
        BrowserTakeoverService.AgentOutcome outcome = rig.service.agentStatus(rig.key);
        assertThat(outcome.state()).isEqualTo("AGENT_CONTROL");
        assertThat(outcome.message())
                .contains("The user finished the requested step")
                .contains("Page now: shop.example/login")
                .contains("[redacted:secret]")
                .doesNotContain(PASSWORD)
                .doesNotContain(CODE)
                .doesNotContain("token=abc");
        assertThat(rig.service.agentStatus(rig.key).message())
                .as("note is handed out once")
                .doesNotContain("Page now");

        // every view the person's client or the agent can fetch is free of it too
        for (String text :
                List.of(
                        String.valueOf(ended),
                        String.valueOf(
                                rig.service.pause(TENANT, USER, AGENT, CHAT_SESSION, "o", "r")))) {
            assertThat(text).doesNotContain(PASSWORD).doesNotContain(CODE);
        }

        // no log line, at any level, from this package carries it
        for (ILoggingEvent event : logs.list) {
            assertThat(event.getFormattedMessage()).doesNotContain(PASSWORD).doesNotContain(CODE);
            assertThat(String.valueOf(event.getThrowableProxy()))
                    .doesNotContain(PASSWORD)
                    .doesNotContain(CODE);
        }

        // and no audit entry does either
        ArgumentCaptor<String> detail = ArgumentCaptor.forClass(String.class);
        verify(rig.audit, timeout(3_000).atLeast(3))
                .record(
                        eq(USER),
                        anyString(),
                        eq("BrowserSession"),
                        anyString(),
                        eq(AuditOutcome.SUCCESS),
                        detail.capture());
        for (String d : detail.getAllValues()) {
            assertThat(d)
                    .doesNotContain(PASSWORD)
                    .doesNotContain(CODE)
                    .doesNotContain("shop.example")
                    .doesNotContain("Solve the captcha");
        }
    }

    @Test
    void shortOrDeletedKeystrokesAreNotKeptAsScrubCandidates() {
        Rig rig = rig();
        Begin begin = takeControl(rig);
        UUID id = id(begin);
        for (String k : new String[] {"a", "b", "Backspace", "Backspace", "Tab"}) {
            rig.service.input(TENANT, USER, id, begin.token(), key(k));
        }
        rig.service.handBack(TENANT, USER, id);
        assertThat(rig.driver.resumeCandidates).isEmpty();
    }

    @Test
    void ifTheReadBackFailsTheAgentStillGetsItsPageBackWithAGenericNote() {
        Rig rig = rig();
        Begin begin = takeControl(rig);
        rig.service.setDriver(
                new BrowserTakeoverFixtures.FakeDriver() {
                    @Override
                    public ResumeSnapshot resume(
                            String sessionKey,
                            UUID agentId,
                            java.util.Collection<String> typed,
                            int maxChars) {
                        throw new BrowserUseException("boom " + PASSWORD);
                    }
                });
        rig.service.handBack(TENANT, USER, id(begin));
        rig.service.requireAgentControl(rig.key);
        assertThat(rig.service.agentStatus(rig.key).message())
                .contains("could not be read back")
                .doesNotContain(PASSWORD);
    }

    @Test
    void aPageThatEndedOffSiteIsBlankedAndTheAgentIsToldWhyWithoutItsText() {
        Rig rig = rig();
        Begin begin = takeControl(rig);
        rig.driver.offSite = true;
        rig.service.handBack(TENANT, USER, id(begin));
        assertThat(rig.service.agentStatus(rig.key).message())
                .contains("outside the sites")
                .doesNotContain("Signed in");
    }

    // ---- clocks
    // -------------------------------------------------------------------------------------------

    @Test
    void anIdleUserIsReturnedToTheAgentAfterTheIdleTimeoutAndActivityKeepsTheirSeat() {
        Rig rig = rig();
        Begin begin = takeControl(rig);
        UUID id = id(begin);
        rig.advance(290);
        String fresh =
                rig.service
                        .refreshToken(TENANT, USER, id)
                        .token(); // the 120 s token has long expired
        rig.service.input(TENANT, USER, id, fresh, key("Tab")); // activity resets the clock
        rig.advance(290);
        rig.service.reap(rig.now.get());
        assertThat(rig.service.controlFor(rig.key).state())
                .isEqualTo(BrowserTakeoverState.USER_CONTROL);
        rig.advance(11);
        rig.service.reap(rig.now.get());
        assertThat(rig.service.controlFor(rig.key).state())
                .isEqualTo(BrowserTakeoverState.AGENT_CONTROL);
        rig.service.requireAgentControl(rig.key);
        assertThat(rig.service.agentStatus(rig.key).message()).contains("idle");
        assertThat(rig.driver.closed).isFalse();
    }

    @Test
    void withIdleActionExpireAnIdleTakeoverEndsTheSession() {
        Rig rig = new Rig(BrowserTakeoverFixtures.settings(true, true, "expire", false));
        takeControl(rig);
        rig.advance(301);
        rig.service.reap(rig.now.get());
        assertThat(rig.driver.closed).isTrue();
        assertThat(rig.service.controlFor(rig.key)).isNull();
    }

    @Test
    void theHardMaximumEndsATakeoverEvenIfTheUserKeepsTyping() {
        Rig rig = rig();
        Begin begin = takeControl(rig);
        for (int minute = 0; minute < 16; minute++) {
            rig.advance(60);
            try {
                rig.service.input(
                        TENANT,
                        USER,
                        id(begin),
                        rig.service.refreshToken(TENANT, USER, id(begin)).token(),
                        key("Tab"));
            } catch (ResponseStatusException ignored) {
                // gone
            }
            rig.service.reap(rig.now.get());
        }
        assertThat(rig.driver.closed).isTrue();
        assertThat(rig.service.controlFor(rig.key)).isNull();
        assertStatus(
                () -> rig.service.input(TENANT, USER, id(begin), begin.token(), key("Tab")),
                HttpStatus.NOT_FOUND);
    }

    @Test
    void anUnansweredRequestExpiresAndTheAgentFindsTheSessionGone() {
        Rig rig = rig();
        rig.service.agentRequest(rig.key, "login", "x");
        rig.advance(599);
        rig.service.reap(rig.now.get());
        assertThat(rig.service.controlFor(rig.key).state())
                .isEqualTo(BrowserTakeoverState.PAUSED_FOR_USER);
        rig.advance(2);
        rig.service.reap(rig.now.get());
        assertThat(rig.driver.closed).isTrue();
        assertThat(rig.service.agentStatus(rig.key).state()).isEqualTo("NO_SESSION");
    }

    @Test
    void closingTheBrowserSessionCancelsTheTakeover() {
        Rig rig = rig();
        Begin begin = takeControl(rig);
        rig.service.onSessionClosed(rig.key);
        assertThat(rig.service.controlFor(rig.key)).isNull();
        assertStatus(
                () -> rig.service.input(TENANT, USER, id(begin), begin.token(), key("Tab")),
                HttpStatus.NOT_FOUND);
    }

    @Test
    void cancelEndsTheSessionForGood() {
        Rig rig = rig();
        Begin begin = takeControl(rig);
        View view = rig.service.cancel(TENANT, USER, id(begin));
        assertThat(view.state()).isEqualTo("CANCELLED");
        assertThat(rig.driver.closed).isTrue();
        assertThat(rig.service.controlFor(rig.key)).isNull();
    }
}
