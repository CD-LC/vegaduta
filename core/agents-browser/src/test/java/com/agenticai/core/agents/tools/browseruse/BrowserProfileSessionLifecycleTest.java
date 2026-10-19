package com.agenticai.core.agents.tools.browseruse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.agenticai.core.security.TenantContext;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Frame;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Request;
import com.microsoft.playwright.Route;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;

/**
 * Profile hooks on the browser session lifecycle: flag-off identity (no database access, no state,
 * no route), the load-on-start / save-on-end wiring under the right tenant, and the
 * top-level-navigation guard.
 */
class BrowserProfileSessionLifecycleTest {

    private static final UUID AGENT = UUID.fromString("11111111-2222-3333-4444-555555555555");
    private static final String KEY = "tenant-1|" + AGENT + "|session-9";

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @SuppressWarnings("unchecked")
    private static BrowserProfileSessionLifecycle lifecycleOver(BrowserProfileService service) {
        ObjectProvider<BrowserProfileService> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(service);
        return new BrowserProfileSessionLifecycle(provider);
    }

    @Test
    void parsesTheAgentOutOfTheManagersSessionKey() {
        assertThat(BrowserSessionLifecycle.agentIdFromKey(KEY)).isEqualTo(AGENT);
        // a free-form session id may itself contain the separator
        assertThat(BrowserSessionLifecycle.agentIdFromKey("t|" + AGENT + "|a|b|c"))
                .isEqualTo(AGENT);
        assertThat(BrowserSessionLifecycle.agentIdFromKey("t|not-a-uuid|s")).isNull();
        assertThat(BrowserSessionLifecycle.agentIdFromKey("nonsense")).isNull();
        assertThat(BrowserSessionLifecycle.agentIdFromKey(null)).isNull();
    }

    @Test
    void withTheFlagOffEveryHookIsANoOpAndNothingTouchesTheDatabase() {
        BrowserProfileService service = mock(BrowserProfileService.class);
        when(service.isEnabled()).thenReturn(false);
        BrowserProfileSessionLifecycle lifecycle = lifecycleOver(service);
        Page page = mock(Page.class);

        assertThat(lifecycle.initialStorageState("tenant-1", KEY)).isNull();
        lifecycle.onSessionCreated("tenant-1", KEY, page);
        lifecycle.onSessionClosing("tenant-1", KEY, page);

        assertThat(lifecycle.activeCount()).isZero();
        verifyNoInteractions(page);
        verify(service, never()).loadForSession(any());
        verify(service, never()).saveState(any(), any());
    }

    @Test
    void withNoServiceBeanEveryHookIsANoOp() {
        BrowserProfileSessionLifecycle lifecycle = lifecycleOver(null);

        assertThat(lifecycle.initialStorageState("tenant-1", KEY)).isNull();
        lifecycle.onSessionClosing("tenant-1", KEY, mock(Page.class));
        assertThat(lifecycle.activeCount()).isZero();
    }

    @Test
    void anAgentWithoutAProfileGetsACleanContext() {
        BrowserProfileService service = mock(BrowserProfileService.class);
        when(service.isEnabled()).thenReturn(true);
        when(service.loadForSession(AGENT)).thenReturn(Optional.empty());
        BrowserProfileSessionLifecycle lifecycle = lifecycleOver(service);

        assertThat(lifecycle.initialStorageState("tenant-1", KEY)).isNull();
        assertThat(lifecycle.activeCount()).isZero();
    }

    @Test
    void startLoadsTheProfileUnderTheSessionsTenantAndRestoresTheContext() {
        BrowserProfileService service = mock(BrowserProfileService.class);
        when(service.isEnabled()).thenReturn(true);
        AtomicReference<String> tenantSeenByService = new AtomicReference<>();
        when(service.loadForSession(AGENT))
                .thenAnswer(
                        inv -> {
                            tenantSeenByService.set(
                                    TenantContext.current()
                                            .map(TenantContext::getTenantId)
                                            .orElse(null));
                            return Optional.of(
                                    new BrowserProfileService.LoadedProfile(
                                            UUID.randomUUID(),
                                            "Work",
                                            List.of("app.example.com"),
                                            "{\"cookies\":[]}"));
                        });
        BrowserProfileSessionLifecycle lifecycle = lifecycleOver(service);
        TenantContext.set(TenantContext.of("someone-else", "u", Set.of()));

        String state = lifecycle.initialStorageState("tenant-1", KEY);

        assertThat(state).isEqualTo("{\"cookies\":[]}");
        assertThat(tenantSeenByService.get()).isEqualTo("tenant-1");
        assertThat(TenantContext.current().orElseThrow().getTenantId()).isEqualTo("someone-else");
        assertThat(lifecycle.activeCount()).isEqualTo(1);
    }

    @Test
    void aProfileWithNoSavedStateStillRegistersSoItIsSavedAtTheEnd() {
        BrowserProfileService service = mock(BrowserProfileService.class);
        when(service.isEnabled()).thenReturn(true);
        UUID profileId = UUID.randomUUID();
        when(service.loadForSession(AGENT))
                .thenReturn(
                        Optional.of(
                                new BrowserProfileService.LoadedProfile(
                                        profileId, "Work", List.of("app.example.com"), null)));
        when(service.saveState(eq(profileId), any()))
                .thenReturn(BrowserProfileService.SaveResult.SAVED);
        BrowserProfileSessionLifecycle lifecycle = lifecycleOver(service);
        Page page = mock(Page.class);
        BrowserContext context = mock(BrowserContext.class);
        when(page.context()).thenReturn(context);
        when(context.storageState())
                .thenReturn("{\"cookies\":[{\"name\":\"sid\"}],\"origins\":[]}");

        assertThat(lifecycle.initialStorageState("tenant-1", KEY)).isNull();
        assertThat(lifecycle.activeCount()).isEqualTo(1);
        lifecycle.onSessionClosing("tenant-1", KEY, page);

        verify(service).saveState(profileId, "{\"cookies\":[{\"name\":\"sid\"}],\"origins\":[]}");
        assertThat(lifecycle.activeCount()).isZero();
    }

    private static final String LOGGED_IN =
            "{\"cookies\":[{\"name\":\"sid\",\"value\":\"a\",\"domain\":\"app.example.com\",\"path\":\"/\"}],\"origins\":[]}";
    private static final String LOGGED_IN_REFRESHED =
            "{\"cookies\":[{\"name\":\"sid\",\"value\":\"b\",\"domain\":\"app.example.com\",\"path\":\"/\"}],\"origins\":[]}";

    @Test
    void aCheckpointSavesOnlyWhenTheFilteredStateChangedAndTheCloseDoesNotRepeatIt() {
        BrowserProfileService service = mock(BrowserProfileService.class);
        when(service.isEnabled()).thenReturn(true);
        UUID profileId = UUID.randomUUID();
        when(service.loadForSession(AGENT))
                .thenReturn(
                        Optional.of(
                                new BrowserProfileService.LoadedProfile(
                                        profileId, "Work", List.of("example.com"), null)));
        when(service.saveState(eq(profileId), any()))
                .thenReturn(BrowserProfileService.SaveResult.SAVED);
        BrowserProfileSessionLifecycle lifecycle = lifecycleOver(service);
        Page page = mock(Page.class);
        BrowserContext context = mock(BrowserContext.class);
        when(page.context()).thenReturn(context);
        when(context.storageState()).thenReturn(LOGGED_IN);

        lifecycle.initialStorageState("tenant-1", KEY);
        lifecycle.onActionCompleted("tenant-1", KEY, page);
        lifecycle.onActionCompleted("tenant-1", KEY, page);
        verify(service, times(1)).saveState(eq(profileId), any());

        when(context.storageState()).thenReturn(LOGGED_IN_REFRESHED);
        lifecycle.onActionCompleted("tenant-1", KEY, page);
        verify(service, times(2)).saveState(eq(profileId), any());

        // the session ends on exactly the state the last checkpoint saved
        lifecycle.onSessionClosing("tenant-1", KEY, page);
        verify(service, times(2)).saveState(eq(profileId), any());
        assertThat(lifecycle.activeCount()).isZero();
    }

    @Test
    void aCleanSessionDoesNotHitTheDatabaseOnEveryAction() {
        BrowserProfileService service = mock(BrowserProfileService.class);
        when(service.isEnabled()).thenReturn(true);
        UUID profileId = UUID.randomUUID();
        when(service.loadForSession(AGENT))
                .thenReturn(
                        Optional.of(
                                new BrowserProfileService.LoadedProfile(
                                        profileId, "Work", List.of("example.com"), null)));
        when(service.saveState(eq(profileId), any()))
                .thenReturn(BrowserProfileService.SaveResult.EMPTY);
        BrowserProfileSessionLifecycle lifecycle = lifecycleOver(service);
        Page page = mock(Page.class);
        BrowserContext context = mock(BrowserContext.class);
        when(page.context()).thenReturn(context);
        when(context.storageState()).thenReturn("{\"cookies\":[],\"origins\":[]}");

        lifecycle.initialStorageState("tenant-1", KEY);
        lifecycle.onActionCompleted("tenant-1", KEY, page);
        lifecycle.onActionCompleted("tenant-1", KEY, page);
        lifecycle.onActionCompleted("tenant-1", KEY, page);

        verify(service, times(1)).saveState(eq(profileId), any());
    }

    @Test
    void aCheckpointOnASessionWithoutAProfileOrWithTheFlagOffDoesNothing() {
        BrowserProfileService off = mock(BrowserProfileService.class);
        when(off.isEnabled()).thenReturn(false);
        Page page = mock(Page.class);

        lifecycleOver(off).onActionCompleted("tenant-1", KEY, page);
        lifecycleOver(null).onActionCompleted("tenant-1", KEY, page);

        verifyNoInteractions(page);
        verify(off, never()).saveState(any(), any());
    }

    @SuppressWarnings("unchecked")
    @Test
    void aRefusedNavigationLeavesAReasonTheFailingNavigateCanReadOnce() {
        BrowserProfileService service = mock(BrowserProfileService.class);
        when(service.isEnabled()).thenReturn(true);
        when(service.restrictNavigation()).thenReturn(true);
        when(service.loadForSession(AGENT))
                .thenReturn(
                        Optional.of(
                                new BrowserProfileService.LoadedProfile(
                                        UUID.randomUUID(), "Work", List.of("example.com"), null)));
        BrowserProfileSessionLifecycle lifecycle = lifecycleOver(service);
        Page page = mock(Page.class);
        lifecycle.initialStorageState("tenant-1", KEY);
        lifecycle.onSessionCreated("tenant-1", KEY, page);
        ArgumentCaptor<Consumer<Route>> handler = ArgumentCaptor.forClass(Consumer.class);
        verify(page).route(eq("**/*"), handler.capture());
        Route route = mock(Route.class);
        Request offDomain = request("https://evil.test/x", true, true);
        when(route.request()).thenReturn(offDomain);

        handler.getValue().accept(route);

        verify(route).abort();
        assertThat(lifecycle.consumeBlockedReason(KEY))
                .contains("browser profile domain scope")
                .contains("example.com");
        assertThat(lifecycle.consumeBlockedReason(KEY)).isNull();
        assertThat(lifecycle.consumeBlockedReason("other|key")).isNull();
    }

    @Test
    void closingASessionThatNeverHadAProfileSavesNothing() {
        BrowserProfileService service = mock(BrowserProfileService.class);
        when(service.isEnabled()).thenReturn(true);
        BrowserProfileSessionLifecycle lifecycle = lifecycleOver(service);
        Page page = mock(Page.class);

        lifecycle.onSessionClosing("tenant-1", KEY, page);

        verify(service, never()).saveState(any(), any());
        verifyNoInteractions(page);
    }

    @Test
    void theNavigationRouteIsInstalledOnlyForAProfileSessionWhenRestrictionIsOn() {
        BrowserProfileService service = mock(BrowserProfileService.class);
        when(service.isEnabled()).thenReturn(true);
        when(service.restrictNavigation()).thenReturn(true);
        when(service.loadForSession(AGENT))
                .thenReturn(
                        Optional.of(
                                new BrowserProfileService.LoadedProfile(
                                        UUID.randomUUID(),
                                        "Work",
                                        List.of("app.example.com"),
                                        null)));
        BrowserProfileSessionLifecycle lifecycle = lifecycleOver(service);
        Page profilePage = mock(Page.class);
        Page otherPage = mock(Page.class);

        lifecycle.initialStorageState("tenant-1", KEY);
        lifecycle.onSessionCreated("tenant-1", KEY, profilePage);
        lifecycle.onSessionCreated("tenant-1", "tenant-1|" + UUID.randomUUID() + "|s", otherPage);

        verify(profilePage).route(eq("**/*"), any());
        verifyNoInteractions(otherPage);

        when(service.restrictNavigation()).thenReturn(false);
        Page unrestricted = mock(Page.class);
        lifecycle.onSessionCreated("tenant-1", KEY, unrestricted);
        verifyNoInteractions(unrestricted);
    }

    // ---- navigation guard
    // ------------------------------------------------------------------------

    private static Request request(String url, boolean navigation, boolean mainFrame) {
        Request request = mock(Request.class);
        Frame frame = mock(Frame.class);
        Frame parent = mainFrame ? null : mock(Frame.class);
        when(request.url()).thenReturn(url);
        when(request.isNavigationRequest()).thenReturn(navigation);
        when(request.frame()).thenReturn(frame);
        when(frame.parentFrame()).thenReturn(parent);
        return request;
    }

    @Test
    void blocksOnlyTopLevelNavigationOutsideTheAllowedDomains() {
        List<String> allowed = List.of("example.com");

        assertThat(
                        BrowserProfileSessionLifecycle.shouldBlock(
                                request("https://evil.test/x", true, true), allowed))
                .isTrue();
        assertThat(
                        BrowserProfileSessionLifecycle.shouldBlock(
                                request("https://app.example.com/x", true, true), allowed))
                .isFalse();
        // sub-resources, XHR and iframes are not top-level navigations
        assertThat(
                        BrowserProfileSessionLifecycle.shouldBlock(
                                request("https://cdn.evil.test/app.js", false, true), allowed))
                .isFalse();
        assertThat(
                        BrowserProfileSessionLifecycle.shouldBlock(
                                request("https://evil.test/frame", true, false), allowed))
                .isFalse();
        // not a site at all
        assertThat(
                        BrowserProfileSessionLifecycle.shouldBlock(
                                request("about:blank", true, true), allowed))
                .isFalse();
        assertThat(
                        BrowserProfileSessionLifecycle.shouldBlock(
                                request("data:text/html,hi", true, true), allowed))
                .isFalse();
    }
}
