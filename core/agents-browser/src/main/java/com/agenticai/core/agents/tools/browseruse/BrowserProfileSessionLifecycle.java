package com.agenticai.core.agents.tools.browseruse;

import com.agenticai.core.security.TenantContext;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Request;
import com.microsoft.playwright.Route;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * Wires browser profiles into {@link BrowserUseSessionManager}'s session lifecycle:
 *
 * <ul>
 *   <li><b>start</b> - when a session is created for an agent with an attached profile, the new
 *       context is seeded with the profile's decrypted, domain-filtered storage state (cookies +
 *       localStorage), and a request guard refuses top-level navigation outside the profile's
 *       domains;
 *   <li><b>checkpoint</b> - after every navigate / click / type / read the state is read back and,
 *       only when its domain-filtered fingerprint changed since the last save, saved (so a login is
 *       persisted within the same turn, not minutes later); an unchanged state costs one Playwright
 *       read and a hash, no database access;
 *   <li><b>end</b> - just before the session's context is closed (idle/lifetime reaper, shutdown)
 *       the refreshed state is read back from Playwright and saved (encrypted, size-capped) through
 *       {@link BrowserProfileService#saveState} unless the last checkpoint already saved that exact
 *       state.
 * </ul>
 *
 * With {@code app.browser.profiles.enabled} off (the default) every hook returns immediately
 * without touching the database, so a session behaves exactly as before.
 *
 * <p>The hooks run on the manager's Playwright thread, which has no {@link TenantContext}; each
 * database call is wrapped in {@link #withTenant} and the previous context restored. A session is
 * kept alive across the turns of a conversation and closed by the idle (5 min) / lifetime (30 min)
 * reaper; the checkpoint means a crash or a killed JVM loses at most the last action's changes.
 *
 * <p>When the navigation guard aborts an off-domain top-level navigation it remembers why, so a
 * failed {@code browser_navigate} can tell the model the cause ({@link #consumeBlockedReason}).
 */
@Component
public class BrowserProfileSessionLifecycle implements BrowserSessionLifecycle {

    private static final Logger log = LoggerFactory.getLogger(BrowserProfileSessionLifecycle.class);

    /** What a live session needs to remember about its profile until it closes. */
    private static final class ActiveProfile {
        private final UUID profileId;
        private final List<String> allowedDomains;

        /**
         * Fingerprint of the last domain-filtered state whose save outcome is settled (saved /
         * empty / too large).
         */
        private volatile String lastFingerprint;

        private ActiveProfile(UUID profileId, List<String> allowedDomains) {
            this.profileId = profileId;
            this.allowedDomains = allowedDomains;
        }
    }

    private final ObjectProvider<BrowserProfileService> service;
    private final Map<String, ActiveProfile> active = new ConcurrentHashMap<>();

    /**
     * Session key -> why an off-domain navigation was just refused; read once by the failing
     * navigate.
     */
    private final Map<String, String> blockReasons = new ConcurrentHashMap<>();

    public BrowserProfileSessionLifecycle(ObjectProvider<BrowserProfileService> service) {
        this.service = service;
    }

    @Override
    public String initialStorageState(String tenantId, String sessionKey) {
        BrowserProfileService profileService = enabledService();
        UUID agentId = BrowserSessionLifecycle.agentIdFromKey(sessionKey);
        if (profileService == null || agentId == null || tenantId == null) {
            return null;
        }
        Optional<BrowserProfileService.LoadedProfile> loaded =
                withTenant(tenantId, () -> profileService.loadForSession(agentId));
        if (loaded == null || loaded.isEmpty()) {
            return null;
        }
        BrowserProfileService.LoadedProfile profile = loaded.get();
        ActiveProfile activeProfile =
                new ActiveProfile(profile.profileId(), profile.allowedDomains());
        if (profile.storageStateJson() != null) {
            activeProfile.lastFingerprint =
                    fingerprintOf(profile.storageStateJson(), profile.allowedDomains());
        }
        active.put(sessionKey, activeProfile);
        return profile.storageStateJson();
    }

    @Override
    public void onSessionCreated(String tenantId, String sessionKey, Page page) {
        ActiveProfile profile = active.get(sessionKey);
        BrowserProfileService profileService = enabledService();
        if (profile == null || profileService == null || !profileService.restrictNavigation()) {
            return;
        }
        List<String> allowed = profile.allowedDomains;
        // Registered after the manager's own request guard, so it runs FIRST (Playwright runs the
        // most recently
        // registered matching route first) and hands everything it does not refuse on with
        // fallback().
        page.route("**/*", route -> guardNavigation(route, sessionKey, allowed));
    }

    @Override
    public void onActionCompleted(String tenantId, String sessionKey, Page page) {
        ActiveProfile profile = active.get(sessionKey);
        if (profile == null) {
            return;
        }
        // A successful action supersedes any stale "navigation blocked" note from before it.
        blockReasons.remove(sessionKey);
        BrowserProfileService profileService = enabledService();
        if (profileService == null || tenantId == null) {
            return;
        }
        BrowserProfileService.SaveResult result = persist(profileService, tenantId, profile, page);
        if (result == BrowserProfileService.SaveResult.SAVED) {
            log.info("Browser profile {} state checkpoint: {}", profile.profileId, result);
        }
    }

    @Override
    public void onSessionClosing(String tenantId, String sessionKey, Page page) {
        ActiveProfile profile = active.remove(sessionKey);
        blockReasons.remove(sessionKey);
        BrowserProfileService profileService = enabledService();
        if (profile == null || profileService == null || tenantId == null) {
            return;
        }
        BrowserProfileService.SaveResult result = persist(profileService, tenantId, profile, page);
        log.info(
                "Browser profile {} state save at session end: {}",
                profile.profileId,
                result == null ? "unchanged since the last checkpoint" : result);
    }

    @Override
    public String consumeBlockedReason(String sessionKey) {
        return sessionKey == null ? null : blockReasons.remove(sessionKey);
    }

    /**
     * Reads the context's state and saves it unless its domain-filtered fingerprint equals the last
     * settled one. Returns the save outcome, or {@code null} when nothing was saved because nothing
     * changed.
     */
    private BrowserProfileService.SaveResult persist(
            BrowserProfileService profileService,
            String tenantId,
            ActiveProfile profile,
            Page page) {
        String state = page.context().storageState();
        String fingerprint = fingerprintOf(state, profile.allowedDomains);
        if (fingerprint != null && fingerprint.equals(profile.lastFingerprint)) {
            return null;
        }
        BrowserProfileService.SaveResult result =
                withTenant(tenantId, () -> profileService.saveState(profile.profileId, state));
        if (fingerprint != null
                && (result == BrowserProfileService.SaveResult.SAVED
                        || result == BrowserProfileService.SaveResult.EMPTY
                        || result == BrowserProfileService.SaveResult.TOO_LARGE)) {
            // Settled outcomes: the same state would answer the same again. NOT_FOUND (also a
            // transient DB error) is retried.
            profile.lastFingerprint = fingerprint;
        }
        return result;
    }

    /**
     * SHA-256 (hex) of the state filtered to the profile's domains, or {@code null} when it cannot
     * be computed.
     */
    static String fingerprintOf(String storageStateJson, List<String> allowedDomains) {
        try {
            String filtered = BrowserStorageState.filterToDomains(storageStateJson, allowedDomains);
            byte[] digest =
                    MessageDigest.getInstance("SHA-256")
                            .digest(filtered.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (RuntimeException | java.security.NoSuchAlgorithmException e) {
            return null;
        }
    }

    /**
     * The service when the feature is on, else null (flag-off identity: no DB access on any hook).
     */
    private BrowserProfileService enabledService() {
        BrowserProfileService profileService = service.getIfAvailable();
        return profileService != null && profileService.isEnabled() ? profileService : null;
    }

    private void guardNavigation(Route route, String sessionKey, List<String> allowedDomains) {
        if (shouldBlock(route.request(), allowedDomains)) {
            log.warn("Browser profile blocked top-level navigation outside its allowed domains");
            blockReasons.put(
                    sessionKey,
                    "blocked by this agent's browser profile domain scope (allowed: "
                            + String.join(", ", allowedDomains)
                            + "); the site is not down, this agent may not browse outside those domains");
            route.abort();
            return;
        }
        route.fallback();
    }

    /**
     * True for a TOP-LEVEL (main frame) http(s) navigation to a host outside {@code
     * allowedDomains}. Sub-resources (scripts, images, XHR, iframes) are never blocked here: login
     * pages legitimately pull from CDNs, and their cookies are domain-bound anyway. Non-http(s)
     * URLs (about:blank, data:, blob:) are not navigations to a site.
     */
    static boolean shouldBlock(Request request, List<String> allowedDomains) {
        if (request == null || !request.isNavigationRequest()) {
            return false;
        }
        if (request.frame() == null || request.frame().parentFrame() != null) {
            return false;
        }
        String host = BrowserStorageState.httpHostOf(request.url());
        if (host == null) {
            return false;
        }
        return !BrowserStorageState.hostAllowed(host, allowedDomains);
    }

    @Override
    public Boolean profileAllowsHost(String sessionKey, String host) {
        ActiveProfile profile = active.get(sessionKey);
        if (profile == null) {
            return null;
        }
        return BrowserStorageState.hostAllowed(host, profile.allowedDomains);
    }

    /** Test seam: how many sessions currently hold a profile. */
    int activeCount() {
        return active.size();
    }

    private static <T> T withTenant(String tenantId, Supplier<T> action) {
        Optional<TenantContext> previous = TenantContext.current();
        TenantContext.set(TenantContext.of(tenantId, "browser-profile-runtime", Set.of()));
        try {
            return action.get();
        } finally {
            if (previous.isPresent()) {
                TenantContext.set(previous.get());
            } else {
                TenantContext.clear();
            }
        }
    }
}
