package com.agenticai.core.agents.tools.browseruse;

import static com.agenticai.core.agents.tools.browseruse.BrowserTakeoverFixtures.AGENT;
import static com.agenticai.core.agents.tools.browseruse.BrowserTakeoverFixtures.CHAT_SESSION;
import static com.agenticai.core.agents.tools.browseruse.BrowserTakeoverFixtures.TENANT;
import static com.agenticai.core.agents.tools.browseruse.BrowserTakeoverFixtures.USER;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.agenticai.core.agents.tools.browseruse.BrowserTakeoverDtos.Begin;
import com.agenticai.core.agents.tools.browseruse.BrowserTakeoverFixtures.Rig;
import com.agenticai.core.security.TenantContext;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Keyboard;
import com.microsoft.playwright.Mouse;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.ViewportSize;
import java.lang.reflect.Field;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;

/**
 * The session manager's takeover seams, run against MOCK Playwright objects (the module has no
 * browser): that every agent entry point is refused while the person holds the page, that the
 * person's operations reach the page, that an idle session the person holds is not reaped, and the
 * production {@link ManagerBrowserTakeoverDriver} - click / key / type / scroll / frame / read-back
 * / site rule - over a mock {@link Page}.
 *
 * <p>What this does NOT prove: that real Chromium actually renders the frames or accepts the
 * relayed events.
 */
class BrowserTakeoverManagerTest {

    private BrowserUseSessionManager manager;
    private Rig rig;
    private BrowserContext context;
    private Page page;
    private Mouse mouse;
    private Keyboard keyboard;
    private BrowserUseV2Settings v2;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() throws Exception {
        manager = new BrowserUseSessionManager("true", "", 2, 10, 300, 1800, 20_000, 15_000);
        // The *.example hosts never resolve; answer them with a public address so the SEC-009
        // post-action "still public" check sees what it would see on a real public site.
        manager.useResolverForTesting(
                host ->
                        new java.net.InetAddress[] {
                            java.net.InetAddress.getByName("93.184.216.34")
                        });
        v2 =
                new BrowserUseV2Settings(
                        true,
                        120,
                        80,
                        40,
                        400,
                        false,
                        4,
                        8,
                        false,
                        "shop.example",
                        "",
                        "",
                        "",
                        "",
                        BrowserTakeoverFixtures.provider(null));
        manager.setV2Settings(v2);
        rig = new Rig(BrowserTakeoverFixtures.settings(true, true, "return", true));
        manager.setTakeover(rig.service); // installs the production driver ...
        rig.service.setDriver(
                rig.driver); // ... which the state-machine tests below swap for the fake where they
        // want to

        context = mock(BrowserContext.class);
        page = mock(Page.class);
        mouse = mock(Mouse.class);
        keyboard = mock(Keyboard.class);
        when(page.mouse()).thenReturn(mouse);
        when(page.keyboard()).thenReturn(keyboard);
        when(page.viewportSize()).thenReturn(new ViewportSize(1280, 800));
        when(page.url()).thenReturn("https://shop.example/account?session=secret");
        when(page.innerText("body")).thenReturn("Welcome back. Your code 424242 was accepted.");
        injectSession(Instant.now());
    }

    @AfterEach
    void tearDown() {
        manager.shutdown();
    }

    private void injectSession(Instant createdAt) throws Exception {
        Field field = BrowserUseSessionManager.class.getDeclaredField("sessions");
        field.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<String, BrowserUseSession> sessions =
                (Map<String, BrowserUseSession>) field.get(manager);
        sessions.put(rig.key, new BrowserUseSession(rig.key, TENANT, context, page, createdAt));
    }

    private Begin takeControl() {
        rig.service.agentRequest(rig.key, "login", "Log in");
        UUID id =
                UUID.fromString(rig.service.status(TENANT, USER, AGENT, CHAT_SESSION).takeoverId());
        return rig.service.begin(TENANT, USER, id);
    }

    // ---- the gate
    // -----------------------------------------------------------------------------------------

    @Test
    void everyAgentEntryPointRunsWhileTheAgentIsInControl() {
        assertThat(manager.readPageText(TENANT, AGENT, CHAT_SESSION)).contains("Welcome back");
        manager.screenshot(TENANT, AGENT, CHAT_SESSION);
        AtomicBoolean ran = new AtomicBoolean();
        manager.withSessionPage(TENANT, AGENT, CHAT_SESSION, p -> ran.getAndSet(true));
        manager.runV2(TENANT, AGENT, CHAT_SESSION, (session, p, settings) -> ran.getAndSet(true));
        assertThat(ran).isTrue();
    }

    @Test
    void noAgentActionLeaksThroughWhileTheUserHasControlOrIsAboutTo() throws Exception {
        // no domain list, so navigate reaches the takeover gate (a literal public IP needs no DNS)
        manager.setV2Settings(
                new BrowserUseV2Settings(
                        true,
                        120,
                        80,
                        40,
                        400,
                        false,
                        4,
                        8,
                        false,
                        "",
                        "",
                        "",
                        "",
                        "",
                        BrowserTakeoverFixtures.provider(null)));
        for (boolean userDriving : new boolean[] {false, true}) {
            if (userDriving) {
                takeControl();
            } else {
                rig.service.agentRequest(rig.key, "login", "Log in"); // PAUSED_FOR_USER
            }
            AtomicBoolean ran = new AtomicBoolean();
            List<Runnable> agentCalls =
                    List.of(
                            () ->
                                    manager.navigate(
                                            TENANT, AGENT, CHAT_SESSION, "https://93.184.216.34/"),
                            () -> manager.click(TENANT, AGENT, CHAT_SESSION, "button#pay"),
                            () -> manager.type(TENANT, AGENT, CHAT_SESSION, "input#pw", "x"),
                            () -> manager.readPageText(TENANT, AGENT, CHAT_SESSION),
                            () -> manager.screenshot(TENANT, AGENT, CHAT_SESSION),
                            () ->
                                    manager.withSessionPage(
                                            TENANT, AGENT, CHAT_SESSION, p -> ran.getAndSet(true)),
                            () ->
                                    manager.runV2(
                                            TENANT,
                                            AGENT,
                                            CHAT_SESSION,
                                            (session, p, settings) -> ran.getAndSet(true)));
            for (Runnable call : agentCalls) {
                assertThatThrownBy(call::run)
                        .isInstanceOf(BrowserUseException.class)
                        .hasMessageMatching("(?s).*(paused for the user|currently has control).*");
            }
            assertThat(ran).as("no operation body ran").isFalse();
            verifyNoInteractions(mouse, keyboard);
            verify(page, never()).innerText(anyString());
            verify(page, never()).screenshot(any(Page.ScreenshotOptions.class));
            verify(page, never()).navigate(anyString());
            verify(page, never()).locator(anyString());
        }
    }

    @Test
    void theV2ToolCallbacksReportTheRefusalToTheModelInsteadOfActing() {
        takeControl();
        TenantContext.set(TenantContext.of(TENANT, USER, Set.of()));
        try {
            List<ToolCallback> tools =
                    List.of(
                            new BrowserSnapshotToolCallback(manager, AGENT, CHAT_SESSION),
                            new BrowserScrollToolCallback(manager, AGENT, CHAT_SESSION),
                            new BrowserExtractTextToolCallback(manager, AGENT, CHAT_SESSION),
                            new BrowserReadPageTextToolCallback(manager, AGENT, CHAT_SESSION),
                            new BrowserScreenshotToolCallback(manager, AGENT, CHAT_SESSION));
            for (ToolCallback tool : tools) {
                String result = tool.call("{\"index\":1,\"text\":\"x\"}");
                assertThat(result)
                        .as(tool.getToolDefinition().name())
                        .contains("error")
                        .contains("currently has control");
            }
            verify(page, never()).innerText(anyString());
            verifyNoInteractions(mouse, keyboard);
        } finally {
            TenantContext.clear();
        }
    }

    @Test
    void afterHandBackTheAgentActsAgain() throws Exception {
        Begin begin = takeControl();
        rig.service.handBack(TENANT, USER, UUID.fromString(begin.view().takeoverId()));
        assertThat(manager.readPageText(TENANT, AGENT, CHAT_SESSION)).contains("Welcome back");
    }

    @Test
    void aSessionTheUserHoldsIsNotReapedForAgentIdlenessButAnOrdinaryIdleOneIs() throws Exception {
        injectSession(
                Instant.now()
                        .minusSeconds(400)); // idle past 300 s, younger than the 1800 s lifetime
        takeControl();
        manager.sweepExpiredSessions();
        manager.runForUser(
                rig.key, false, s -> null); // FIFO barrier: the sweep ran before this returns
        verify(context, never()).close();

        rig.service.handBack(
                TENANT,
                USER,
                UUID.fromString(rig.service.controlFor(rig.key).takeoverId().toString()));
        injectSession(Instant.now().minusSeconds(400));
        manager.sweepExpiredSessions();
        verify(context, timeout(3_000)).close();
        assertThat(rig.service.controlFor(rig.key))
                .as("closing the session ends its takeover state")
                .isNull();
    }

    // ---- the person's operations over the production driver
    // -----------------------------------------------

    @Test
    void thePersonsInputReachesThePageAndFramesAreBounded() throws Exception {
        rig.service.setDriver(new ManagerBrowserTakeoverDriver(manager));
        Begin begin = takeControl();
        UUID id = UUID.fromString(begin.view().takeoverId());
        when(page.screenshot(any(Page.ScreenshotOptions.class))).thenReturn(jpeg(1280, 800));

        rig.service.input(
                TENANT,
                USER,
                id,
                begin.token(),
                new BrowserTakeoverDtos.InputEvent(
                        "click", 256, 160, 1024, 640, null, null, null, null));
        verify(mouse).click(320.0, 200.0);
        rig.service.input(
                TENANT,
                USER,
                id,
                begin.token(),
                new BrowserTakeoverDtos.InputEvent(
                        "type", null, null, null, null, null, "hello", null, null));
        verify(keyboard).type("hello");
        rig.service.input(
                TENANT,
                USER,
                id,
                begin.token(),
                new BrowserTakeoverDtos.InputEvent(
                        "key", null, null, null, null, "Enter", null, null, null));
        verify(keyboard).press("Enter");
        rig.service.input(
                TENANT,
                USER,
                id,
                begin.token(),
                new BrowserTakeoverDtos.InputEvent("scroll", 5, 6, null, null, null, null, 0, 300));
        verify(mouse).wheel(0.0, 300.0);

        int epoch = rig.service.openStream(TENANT, USER, id, begin.token());
        BrowserTakeoverDtos.FrameEvent frame = rig.service.captureFrame(TENANT, USER, id, epoch);
        assertThat(Math.max(frame.width(), frame.height()))
                .as("long side capped at 1024")
                .isEqualTo(1024);
        assertThat(java.util.Base64.getDecoder().decode(frame.data())).isNotEmpty();
    }

    @Test
    void theReadBackScrubsWhatThePersonTypedAndEveryVaultValueTypedEarlier() throws Exception {
        rig.service.setDriver(new ManagerBrowserTakeoverDriver(manager));
        // a vault value the AGENT typed earlier in this session
        manager.runForUser(
                rig.key,
                false,
                session -> {
                    session.v2(v2).rememberSecretValues(List.of("Welcome back"));
                    return null;
                });
        Begin begin = takeControl();
        UUID id = UUID.fromString(begin.view().takeoverId());
        for (BrowserTakeoverDtos.InputEvent e :
                List.of(
                        new BrowserTakeoverDtos.InputEvent(
                                "type", null, null, null, null, null, "424242", null, null),
                        new BrowserTakeoverDtos.InputEvent(
                                "key", null, null, null, null, "Enter", null, null, null))) {
            rig.service.input(TENANT, USER, id, begin.token(), e);
        }
        rig.service.handBack(TENANT, USER, id);
        String note = rig.service.agentStatus(rig.key).message();
        assertThat(note)
                .contains("Page now: shop.example/account")
                .contains("[redacted:secret]")
                .doesNotContain("424242")
                .doesNotContain("Welcome back")
                .doesNotContain("session=secret");
        // and later reads by the agent stay scrubbed: the typed value joined the session's
        // redaction set
        assertThat(manager.readPageText(TENANT, AGENT, CHAT_SESSION)).doesNotContain("424242");
    }

    @Test
    void theReadBackBlanksAPageThatEndedOutsideThePolicy() throws Exception {
        rig.service.setDriver(new ManagerBrowserTakeoverDriver(manager));
        Begin begin = takeControl();
        when(page.url()).thenReturn("https://evil.example/phish");
        rig.service.handBack(TENANT, USER, UUID.fromString(begin.view().takeoverId()));
        verify(page).navigate("about:blank");
        assertThat(rig.service.agentStatus(rig.key).message())
                .contains("outside the sites")
                .doesNotContain("Welcome back");
    }

    @Test
    void theSiteRuleHonoursThePolicyTheProfileAndTheExplicitAllowListRequirement() {
        ManagerBrowserTakeoverDriver driver = new ManagerBrowserTakeoverDriver(manager);
        String key = rig.key;
        assertThat(driver.siteRefusal(key, AGENT, "https://shop.example/login"))
                .as("on the allow-list")
                .isNull();
        assertThat(driver.siteRefusal(key, AGENT, "https://sub.shop.example/login"))
                .as("subdomain of an allow-list entry is not in an exact-host list")
                .isNotNull();
        assertThat(driver.siteRefusal(key, AGENT, "https://other.example/"))
                .contains("not in the domains");
        assertThat(driver.siteRefusal(key, AGENT, "about:blank")).contains("normal web page");
        assertThat(driver.siteRefusal(key, AGENT, "file:///etc/passwd"))
                .contains("normal web page");

        manager.setLifecycles(
                List.of(
                        new BrowserSessionLifecycle() {
                            @Override
                            public Boolean profileAllowsHost(String sessionKey, String host) {
                                return false;
                            }
                        }));
        assertThat(driver.siteRefusal(key, AGENT, "https://shop.example/login"))
                .contains("saved browser profile");
    }

    @Test
    void withNoAllowListAtAllTakeoverIsRefusedUnlessTheOperatorOptsOut() {
        BrowserUseV2Settings open =
                new BrowserUseV2Settings(
                        true,
                        120,
                        80,
                        40,
                        400,
                        false,
                        4,
                        8,
                        false,
                        "",
                        "",
                        "",
                        "",
                        "",
                        BrowserTakeoverFixtures.provider(null));
        manager.setV2Settings(open);
        ManagerBrowserTakeoverDriver driver = new ManagerBrowserTakeoverDriver(manager);
        assertThat(driver.siteRefusal(rig.key, AGENT, "https://anywhere.example/"))
                .contains("allow-listed");
        // a profile that covers the host counts as an explicit allow-list
        manager.setLifecycles(
                List.of(
                        new BrowserSessionLifecycle() {
                            @Override
                            public Boolean profileAllowsHost(String sessionKey, String host) {
                                return true;
                            }
                        }));
        assertThat(driver.siteRefusal(rig.key, AGENT, "https://anywhere.example/")).isNull();

        // the operator may opt out of the explicit-allow-list requirement
        Rig relaxed = new Rig(BrowserTakeoverFixtures.settings(true, true, "return", false));
        manager.setTakeover(relaxed.service);
        manager.setLifecycles(List.of());
        assertThat(driver.siteRefusal(rig.key, AGENT, "https://anywhere.example/")).isNull();
    }

    @Test
    void aStreamedFrameOfAnEmptyScreenshotFailsGenericallyNotWithAStackTrace() {
        rig.service.setDriver(new ManagerBrowserTakeoverDriver(manager));
        Begin begin = takeControl();
        UUID id = UUID.fromString(begin.view().takeoverId());
        when(page.screenshot(any(Page.ScreenshotOptions.class))).thenReturn(new byte[] {1, 2, 3});
        int epoch = rig.service.openStream(TENANT, USER, id, begin.token());
        assertThatThrownBy(() -> rig.service.captureFrame(TENANT, USER, id, epoch))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
    }

    private static byte[] jpeg(int w, int h) throws Exception {
        java.awt.image.BufferedImage image =
                new java.awt.image.BufferedImage(w, h, java.awt.image.BufferedImage.TYPE_INT_RGB);
        return BrowserTakeoverFrames.encode(image, 60);
    }
}
