package com.agenticai.core.agents.tools.browseruse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.agenticai.core.agents.tools.browseruse.BrowserTakeoverDtos.Begin;
import com.agenticai.core.agents.tools.browseruse.BrowserTakeoverDtos.InputEvent;
import com.agenticai.core.agents.tools.browseruse.BrowserTakeoverDtos.View;
import com.agenticai.core.audit.AuditLogService;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * Shared test doubles for the takeover tests: settings builder, a fake page driver, a controllable
 * clock.
 */
final class BrowserTakeoverFixtures {

    static final Instant T0 = Instant.parse("2026-10-05T10:00:00Z");
    static final String TENANT = "tenant-1";
    static final String USER = "user-1";
    static final UUID AGENT = UUID.fromString("11111111-1111-1111-1111-111111111111");
    static final String CHAT_SESSION = "chat-1";

    private BrowserTakeoverFixtures() {}

    @SuppressWarnings("unchecked")
    static <T> ObjectProvider<T> provider(T value) {
        ObjectProvider<T> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(value);
        return provider;
    }

    static BrowserUseV2Settings v2(boolean enabled) {
        BrowserUseV2Settings v2 = mock(BrowserUseV2Settings.class);
        when(v2.isEnabled()).thenReturn(enabled);
        return v2;
    }

    /** Settings with the defaults of application.yml except the knobs a test overrides. */
    static BrowserTakeoverSettings settings(
            boolean enabled, boolean v2On, String idleAction, boolean requireAllowlist) {
        return new BrowserTakeoverSettings(
                enabled,
                provider(v2(v2On)),
                2,
                1024,
                55,
                300,
                idleAction,
                900,
                600,
                120,
                20,
                40,
                256,
                2000,
                4,
                requireAllowlist,
                "test-secret",
                1500,
                200);
    }

    static BrowserTakeoverSettings on() {
        return settings(true, true, "return", false);
    }

    /** A page that records what is done to it and never touches Playwright. */
    static class FakeDriver implements BrowserTakeoverDriver {
        volatile String url = "https://shop.example/login?token=abc";
        volatile String refusal;
        volatile int[] viewport = {1280, 800};
        volatile String pageText = "Signed in. Verification code 9f3k7q accepted for hunter2-Pw.";
        volatile boolean offSite;
        volatile boolean closed;
        volatile boolean failFrames;
        final List<String> calls = new CopyOnWriteArrayList<>();
        final List<String> typed = new CopyOnWriteArrayList<>();
        final List<String> resumeCandidates = new CopyOnWriteArrayList<>();

        @Override
        public String currentUrl(String sessionKey) {
            return url;
        }

        @Override
        public String siteRefusal(String sessionKey, UUID agentId, String url) {
            return refusal;
        }

        @Override
        public Frame frame(String sessionKey, int maxDimension, int quality) {
            if (failFrames) {
                throw new BrowserUseException("no frame");
            }
            calls.add("frame " + maxDimension + "/" + quality);
            return new Frame(new byte[] {1, 2, 3}, 1024, 640, viewport[0], viewport[1]);
        }

        @Override
        public int[] viewport(String sessionKey) {
            return viewport;
        }

        @Override
        public void click(String sessionKey, int x, int y) {
            calls.add("click " + x + "," + y);
        }

        @Override
        public void press(String sessionKey, String key) {
            calls.add("press " + key);
        }

        @Override
        public void typeText(String sessionKey, String text) {
            typed.add(text);
            calls.add("type len=" + text.length());
        }

        @Override
        public void scroll(String sessionKey, int x, int y, int dx, int dy) {
            calls.add("scroll " + dx + "," + dy);
        }

        @Override
        public ResumeSnapshot resume(
                String sessionKey, UUID agentId, Collection<String> typedCandidates, int maxChars) {
            resumeCandidates.addAll(typedCandidates);
            // The production driver scrubs with exactly this function (BrowserV2State#redact ->
            // BrowserSecretPlaceholders#redact).
            String text =
                    BrowserSecretPlaceholders.redact(pageText, new ArrayList<>(typedCandidates));
            if (text.length() > maxChars) {
                text = text.substring(0, maxChars);
            }
            return new ResumeSnapshot(ManagerBrowserTakeoverDriver.hostAndPath(url), text, offSite);
        }

        @Override
        public void closeSession(String sessionKey) {
            closed = true;
        }
    }

    /** A service wired to a fake driver, a fake clock and a recording audit mock. */
    static final class Rig {
        final FakeDriver driver = new FakeDriver();
        final AtomicReference<Instant> now = new AtomicReference<>(T0);
        final AuditLogService audit = mock(AuditLogService.class);
        final BrowserTakeoverService service;
        final String key = BrowserUseSessionManager.sessionKey(TENANT, AGENT, CHAT_SESSION);

        Rig(BrowserTakeoverSettings settings) {
            service = new BrowserTakeoverService(settings, provider(audit));
            service.setDriver(driver);
            service.setClock(now::get);
            service.onSessionCreated(key, TENANT, USER, AGENT);
        }

        void advance(long seconds) {
            now.set(now.get().plusSeconds(seconds));
        }
    }

    // ---- helpers shared by the takeover service tests

    static Rig rig() {
        return new Rig(BrowserTakeoverFixtures.on());
    }

    static InputEvent click(int x, int y, int fw, int fh) {
        return new InputEvent("click", x, y, fw, fh, null, null, null, null);
    }

    static InputEvent key(String key) {
        return new InputEvent("key", null, null, null, null, key, null, null, null);
    }

    static InputEvent type(String text) {
        return new InputEvent("type", null, null, null, null, null, text, null, null);
    }

    /** Walks a rig to USER_CONTROL and returns the begin response (token included). */
    static Begin takeControl(Rig rig) {
        rig.service.agentRequest(rig.key, "captcha", "Solve the captcha");
        View waiting = rig.service.status(TENANT, USER, AGENT, CHAT_SESSION);
        return rig.service.begin(TENANT, USER, UUID.fromString(waiting.takeoverId()));
    }

    static UUID id(Begin begin) {
        return UUID.fromString(begin.view().takeoverId());
    }

    static void assertStatus(Runnable call, HttpStatus expected) {
        assertThatThrownBy(call::run)
                .isInstanceOfSatisfying(
                        ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode().value()).isEqualTo(expected.value()));
    }
}
