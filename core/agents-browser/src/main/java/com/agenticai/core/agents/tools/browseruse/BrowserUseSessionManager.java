package com.agenticai.core.agents.tools.browseruse;

import com.agenticai.core.security.TenantContext;
import com.agenticai.core.security.net.SsrfGuard;
import com.agenticai.core.tenancy.AllowUnlockedSchedule;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Browser-use fallback tool (Lindy "Computer Use" equivalent) - governs the platform's ONE pool of
 * real headless-browser sessions that {@code browser_navigate}/{@code browser_click}/{@code
 * browser_type}/{@code browser_read_page_text}/{@code browser_screenshot} act on. Mirrors {@code
 * com.agenticai.core.devices.DeviceDataIngestionService}'s role for the {@code read_device_*}
 * tools: the one stateful service the tool callbacks in {@code
 * com.agenticai.core.agents.tools.browseruse} delegate to, so each callback's {@code call()} body
 * only handles its own argument parsing and result shaping (see {@link
 * com.agenticai.core.agents.tools.device.DeviceToolSupport}'s javadoc for the same split on the
 * device side).
 *
 * <h2>One Playwright thread</h2>
 *
 * Every Playwright touch runs on one dedicated thread ({@link BrowserUseActionRunner} explains
 * why); that thread is the sole mutator of {@link #sessions}, which is what makes the caps below
 * race-free without locking.
 *
 * <h2>Resource caps (headless sessions are resource-heavy)</h2>
 *
 * <ul>
 *   <li>{@code maxSessionsPerTenant} (default 2) - a hard ceiling on how many concurrent sessions
 *       one tenant may hold, checked at session-creation time inside the single executor thread.
 *   <li>{@code maxTotalSessions} (default 10) - a hard ceiling across every tenant on this
 *       instance, protecting the one JVM process's memory: a headless Chromium context is tens of
 *       MB even idle.
 *   <li>{@code idleTimeoutSeconds} (default 300 = 5 min) - a session with no tool call in this long
 *       is closed by the reaper below.
 *   <li>{@code maxLifetimeSeconds} (default 1800 = 30 min) - an absolute cap regardless of
 *       activity, so a session cannot be kept alive forever by a steady trickle of calls.
 *   <li>{@code navTimeoutMs}/{@code actionTimeoutMs} (default 20s/15s) - the Playwright
 *       per-operation timeouts set on every context, so a hung page cannot block its session (or
 *       the shared executor thread) indefinitely.
 * </ul>
 *
 * All five are {@code @Value}-configurable and intentionally conservative defaults, not tuned for a
 * specific deployment - see {@code app.browseruse.*} properties.
 *
 * <h2>On automatically when a browser exists</h2>
 *
 * {@code app.browseruse.enabled} defaults to {@code auto}: the tool is offered exactly when a
 * Chromium binary is found (the platform image bakes one into {@code PLAYWRIGHT_BROWSERS_PATH}).
 * Chromium is a real binary, and shipping this ON unconditionally would mean every deployment that
 * has not provisioned it gets a working-until-first-use tool that then fails opaquely. {@link
 * #isEnabled()} is what {@code AgentToolProvider} gates the whole tool family on (same "cheap
 * boolean gate at offer-time" shape every other optional built-in in that class uses), and launch
 * itself is additionally lazy - the first real session request, not application startup - so a
 * misconfigured environment fails one tool call with a relayable message instead of failing Spring
 * context refresh for the whole platform.
 *
 * <h2>Domain safety (security review SEC-009)</h2>
 *
 * Every layer is installed by {@link BrowserSessionFactory} (its javadoc lists them, and the
 * residual that needs network egress controls); {@link #assertStillPublic} closes a session that
 * reached a non-public address after any action.
 */
@Component
public class BrowserUseSessionManager {

    private static final Logger log = LoggerFactory.getLogger(BrowserUseSessionManager.class);

    private final boolean enabled;
    private final String unavailableReason;
    private final int maxSessionsPerTenant;
    private final int maxTotalSessions;
    private final long idleTimeoutSeconds;
    private final long maxLifetimeSeconds;
    private final int navTimeoutMs;
    private final int actionTimeoutMs;

    /** Single dedicated thread every Playwright call runs on - see class javadoc. */
    private final BrowserUseActionRunner runner = new BrowserUseActionRunner();

    /**
     * Touched ONLY on {@link #runner}'s thread - see class javadoc. Not a ConcurrentHashMap on
     * purpose: a plain map documents that single-writer invariant instead of hiding it behind a
     * concurrent structure nothing actually races on.
     */
    private final Map<String, BrowserUseSession> sessions = new HashMap<>();

    /** Session lifecycle seam (browser profiles); no-op without observers. */
    private final BrowserSessionLifecycles lifecycles =
            new BrowserSessionLifecycles(key -> this.sessions.get(key));

    /** The lazily launched driver + Chromium; touched only on the Playwright thread. */
    private final BrowserUsePlaywright playwright;

    /** Builds sessions with the SEC-009 layers; owns the taint set and the URL guard. */
    private final BrowserSessionFactory sessionFactory;

    /**
     * Browser agent v2 settings + domain policy (see {@link BrowserUseV2Settings}). Setter-injected
     * and optional so this class's constructor signature - and every test that constructs it - is
     * unchanged; null (slim contexts) behaves exactly like v2 off with no domain lists.
     */
    private volatile BrowserUseV2Settings v2Settings;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setV2Settings(BrowserUseV2Settings v2Settings) {
        this.v2Settings = v2Settings;
    }

    /**
     * True when the browser is available AND browser agent v2 ({@code app.browser.v2.enabled}) is
     * on - the gate for offering the v2 tools.
     */
    public boolean isV2Enabled() {
        BrowserUseV2Settings settings = v2Settings;
        return enabled && settings != null && settings.isEnabled();
    }

    /**
     * Whether the legacy {@code browser_click}/{@code browser_type} remain offered alongside the v2
     * tools (default false: they bypass the irreversible-click gate).
     */
    public boolean isLegacyClickTypeOffered() {
        BrowserUseV2Settings settings = v2Settings;
        return settings == null || settings.isLegacyClickTypeEnabled();
    }

    /** The v2 settings, or null when the bean is absent. */
    BrowserUseV2Settings v2Settings() {
        return v2Settings;
    }

    /**
     * Human takeover (app.browser.takeover.enabled, default OFF). Setter-injected and optional so
     * the constructor is untouched; null, or a service whose {@code isActive()} is false, makes
     * every takeover branch below a no-op and behaviour identical to a build without the feature.
     */
    private volatile BrowserTakeoverService takeover;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setTakeover(BrowserTakeoverService takeover) {
        this.takeover = takeover;
        if (takeover != null) {
            takeover.setDriver(new ManagerBrowserTakeoverDriver(this));
        }
    }

    /**
     * Refuses an AGENT action while the person holds the session. The person's own operations never
     * call this.
     */
    private void gateAgent(String key) {
        BrowserTakeoverService service = takeover;
        if (service != null) {
            service.requireAgentControl(key);
        }
    }

    private BrowserDomainPolicy domainPolicy() {
        BrowserUseV2Settings settings = v2Settings;
        return settings == null ? BrowserDomainPolicy.INACTIVE : settings.domainPolicy();
    }

    /**
     * Test seam: the strict guard with a fixed host resolver, so unit tests can use reserved
     * hostnames (e.g. {@code shop.example}) that the system resolver can never answer.
     */
    void useResolverForTesting(SsrfGuard.HostResolver resolver) {
        sessionFactory.useGuard(SsrfGuard.strict().withResolver(resolver));
    }

    /**
     * {@code enabledMode}: {@code true} / {@code false} force the tool on or off; {@code auto} (the
     * default) turns it on exactly when a Chromium binary can be found, so a deployment whose image
     * bakes Chromium in gets the tool out of the box and one without it never offers a tool that
     * would fail on first use.
     */
    BrowserUseSessionManager(
            String enabledMode,
            String chromiumExecutablePath,
            int maxSessionsPerTenant,
            int maxTotalSessions,
            long idleTimeoutSeconds,
            long maxLifetimeSeconds,
            int navTimeoutMs,
            int actionTimeoutMs) {
        this(
                enabledMode,
                chromiumExecutablePath,
                maxSessionsPerTenant,
                maxTotalSessions,
                idleTimeoutSeconds,
                maxLifetimeSeconds,
                navTimeoutMs,
                actionTimeoutMs,
                "");
    }

    /**
     * {@code remoteUrl} ({@code app.browseruse.remote-url}): the personal install's {@code browser}
     * add-on, a Playwright server in its own container. With it set the browser is available in
     * {@code auto} mode without any Chromium in this image.
     */
    @org.springframework.beans.factory.annotation.Autowired
    public BrowserUseSessionManager(
            @Value("${app.browseruse.enabled:auto}") String enabledMode,
            @Value("${app.browseruse.chromium-executable-path:}") String chromiumExecutablePath,
            @Value("${app.browseruse.max-sessions-per-tenant:2}") int maxSessionsPerTenant,
            @Value("${app.browseruse.max-total-sessions:10}") int maxTotalSessions,
            @Value("${app.browseruse.session-idle-timeout-seconds:300}") long idleTimeoutSeconds,
            @Value("${app.browseruse.session-max-lifetime-seconds:1800}") long maxLifetimeSeconds,
            @Value("${app.browseruse.navigation-timeout-ms:20000}") int navTimeoutMs,
            @Value("${app.browseruse.action-timeout-ms:15000}") int actionTimeoutMs,
            @Value("${app.browseruse.remote-url:}") String remoteUrl) {
        this.playwright =
                new BrowserUsePlaywright(
                        BrowserUseChromiumLocator.locate(
                                chromiumExecutablePath, BrowserUsePlaywright.browsersRootFromEnv()),
                        remoteUrl);
        String mode =
                enabledMode == null
                        ? "auto"
                        : enabledMode.trim().toLowerCase(java.util.Locale.ROOT);
        this.enabled =
                switch (mode) {
                    case "true" -> true;
                    case "false" -> false;
                    default -> this.playwright.hasChromium() || this.playwright.usesRemote();
                };
        this.unavailableReason =
                enabled
                        ? null
                        : mode.equals("false")
                                ? "The browser is switched off (app.browseruse.enabled=false)."
                                : "No Chromium was found in this core image. Install the browser"
                                        + " add-on (`scripts/vegaduta personal --browser`, or the full core image,"
                                        + " CORE_IMAGE_VARIANT=-full), or point"
                                        + " app.browseruse.chromium-executable-path at a Chromium binary.";
        this.maxSessionsPerTenant = maxSessionsPerTenant;
        this.maxTotalSessions = maxTotalSessions;
        this.idleTimeoutSeconds = idleTimeoutSeconds;
        this.maxLifetimeSeconds = maxLifetimeSeconds;
        this.navTimeoutMs = navTimeoutMs;
        this.actionTimeoutMs = actionTimeoutMs;
        this.sessionFactory =
                new BrowserSessionFactory(
                        playwright,
                        lifecycles,
                        this::domainPolicy,
                        this::isV2Enabled,
                        navTimeoutMs,
                        actionTimeoutMs);
    }

    /**
     * Cheap, non-throwing gate {@code AgentToolProvider} offers the whole tool family on - see
     * class javadoc.
     */
    public boolean isEnabled() {
        return enabled;
    }

    /**
     * Why {@link #isEnabled()} is false (no Chromium on disk, or switched off), for the console to
     * show "install the browser add-on"; {@code null} when the browser is available.
     */
    public String unavailableReason() {
        return unavailableReason;
    }

    /**
     * Why an enabled browser cannot be used right now: the add-on container is configured but not
     * answering. {@code null} when it is reachable, when Chromium is local, or when the browser is off.
     * Cached briefly: the console polls the capabilities.
     */
    public String remoteProblem() {
        if (!enabled || !playwright.usesRemote()) {
            return null;
        }
        long now = System.nanoTime();
        synchronized (remoteProbeLock) {
            if (now - remoteProbeAt > REMOTE_PROBE_TTL_NANOS || remoteProbeAt == 0) {
                remoteProbeResult = playwright.remoteProblem();
                remoteProbeAt = now == 0 ? 1 : now;
            }
            return remoteProbeResult;
        }
    }

    private static final long REMOTE_PROBE_TTL_NANOS = 10_000_000_000L;
    private final Object remoteProbeLock = new Object();
    private long remoteProbeAt;
    private String remoteProbeResult;

    // ---- entry points used by the tool callbacks ---------------------------

    /**
     * {@code browser_navigate}'s entry point - creates the tenant/agent/session's page if it does
     * not exist yet (subject to the caps), then navigates it. Returns the resulting page title and
     * final URL (after Playwright's own redirect handling).
     */
    NavigateResult navigate(String tenantId, UUID agentId, String sessionId, String requestedUrl) {
        // Validated BEFORE the executor hop too, so a bad/internal URL gets the
        // precise BrowserUseUrlValidator reason immediately rather than a
        // generic "navigation aborted" once the route interceptor catches it.
        java.net.URI validated = BrowserUseUrlValidator.validatePublicHttpUrl(requestedUrl);
        // Operator / per-agent domain allow-deny (app.browser.*-domains). Inactive by default =
        // today's behaviour.
        BrowserDomainPolicy policy = domainPolicy();
        if (policy.isActive()) {
            BrowserDomainPolicy.Decision decision = policy.check(agentId, validated.toString());
            if (!decision.allowed()) {
                throw new BrowserUseException(decision.message());
            }
        }
        String key = sessionKey(tenantId, agentId, sessionId);
        // The subject that started this session owns it for human takeover (read here: the
        // Playwright thread has no TenantContext).
        String owner =
                takeover == null
                        ? null
                        : TenantContext.current().map(TenantContext::getSubject).orElse(null);
        return run(
                actionTimeout(),
                () -> {
                    gateAgent(key);
                    BrowserUseSession session = getOrCreateSession(tenantId, agentId, key, owner);
                    Page page = session.page();
                    BrowserV2State v2State = session.v2OrNull();
                    if (v2State != null) {
                        // A deliberate navigate to a DIFFERENT page is a re-plan: clear the v2
                        // stuck detector's abort latch.
                        // Navigating back to the very page the task was aborted on does not (a
                        // looping model could otherwise
                        // evade the abort forever with navigate-to-same-URL).
                        if (v2State.isAbortedPage(validated.toString())) {
                            throw new BrowserUseException(v2State.detector().abortReason());
                        }
                        v2State.resetForReplan();
                    }
                    session.clearBlocked();
                    try {
                        lifecycles.navigate(key, page, validated.toString());
                    } catch (RuntimeException e) {
                        // The route guard aborts a blocked redirect hop; say WHY instead of a
                        // generic net::ERR_FAILED.
                        String blocked = session.lastBlocked();
                        if (blocked != null) {
                            throw new BrowserUseException(blocked);
                        }
                        throw e;
                    }
                    // SEC-009: where did the redirects actually land?
                    assertStillPublic(key, page);
                    BrowserSessionFactory.enforceFinalUrl(page, agentId, policy);
                    return new NavigateResult(page.url(), BrowserSessionFactory.safeTitle(page));
                });
    }

    /**
     * {@code browser_click} - requires an existing session (call {@code browser_navigate} first).
     */
    void click(String tenantId, UUID agentId, String sessionId, String selectorOrDescription) {
        String key = sessionKey(tenantId, agentId, sessionId);
        run(
                actionTimeout(),
                () -> {
                    BrowserUseSession session = requireSession(key);
                    session.requireNotAborted();
                    Page page = session.page();
                    BrowserUseLocators.performWithFallback(
                            page, selectorOrDescription, Locator::click);
                    assertStillPublic(key, page);
                    lifecycles.checkpoint(key);
                    return null;
                });
    }

    /** {@code browser_type} - requires an existing session. */
    void type(
            String tenantId,
            UUID agentId,
            String sessionId,
            String selectorOrDescription,
            String text) {
        String key = sessionKey(tenantId, agentId, sessionId);
        String value = text == null ? "" : text;
        run(
                actionTimeout(),
                () -> {
                    BrowserUseSession session = requireSession(key);
                    session.requireNotAborted();
                    Page page = session.page();
                    BrowserUseLocators.performWithFallback(
                            page, selectorOrDescription, locator -> locator.fill(value));
                    assertStillPublic(key, page);
                    lifecycles.checkpoint(key);
                    return null;
                });
    }

    /** {@code browser_read_page_text} - requires an existing session. */
    String readPageText(String tenantId, UUID agentId, String sessionId) {
        String key = sessionKey(tenantId, agentId, sessionId);
        return run(
                actionTimeout(),
                () -> {
                    BrowserUseSession session = requireSession(key);
                    Page page = session.page();
                    assertStillPublic(key, page);
                    String text = page.innerText("body");
                    assertStillPublic(key, page); // a script may have navigated while reading
                    lifecycles.checkpoint(key);
                    // Vault values typed through browser_type_into are scrubbed from page text (a
                    // page can echo them back).
                    BrowserV2State v2State = session.v2OrNull();
                    return v2State == null ? text : v2State.redact(text);
                });
    }

    /**
     * {@code browser_screenshot} - requires an existing session. JPEG bytes of the current viewport
     * (not full-page) - JPEG with a moderate quality, not PNG, deliberately: the result is
     * base64-encoded into a JSON tool result that re-enters the model's context, so a compact
     * encoding matters here the same way {@code WebPageFetcher}'s char caps matter for text (see
     * {@link BrowserScreenshotToolCallback}'s javadoc for the output-size cap on the caller side).
     */
    byte[] screenshot(String tenantId, UUID agentId, String sessionId) {
        String key = sessionKey(tenantId, agentId, sessionId);
        return run(
                actionTimeout(),
                () -> {
                    Page page = requireSession(key).page();
                    assertStillPublic(key, page);
                    byte[] image =
                            page.screenshot(
                                    new Page.ScreenshotOptions()
                                            .setType(
                                                    com.microsoft.playwright.options.ScreenshotType
                                                            .JPEG)
                                            .setQuality(60));
                    assertStillPublic(key, page);
                    return image;
                });
    }

    /**
     * An operation run on the executor thread against the session's active page - the v2 tools'
     * entry point.
     */
    @FunctionalInterface
    interface V2Operation<T> {
        T apply(BrowserUseSession session, Page page, BrowserUseV2Settings settings)
                throws Exception;
    }

    /**
     * Runs a browser agent v2 tool's body on the Playwright thread against an EXISTING session (v2
     * tools, like click/type, never create one - {@code browser_navigate} does). A little more
     * timeout headroom than the legacy single-call actions because one v2 action is several
     * Playwright calls (probe, act, settle, fingerprint).
     */
    <T> T runV2(String tenantId, UUID agentId, String sessionId, V2Operation<T> operation) {
        BrowserUseV2Settings settings = v2Settings;
        if (settings == null || !settings.isEnabled()) {
            throw new BrowserUseException("browser agent v2 is not enabled on this platform");
        }
        String key = sessionKey(tenantId, agentId, sessionId);
        return run(
                Duration.ofMillis(Math.max(navTimeoutMs, actionTimeoutMs) + 12_000L),
                () -> {
                    BrowserUseSession session = requireSession(key);
                    T result = operation.apply(session, session.page(), settings);
                    // SEC-009: a v2 action (click, go-back, a new tab) may have landed on a
                    // non-public address.
                    assertStillPublic(key, session.page());
                    return result;
                });
    }

    /**
     * Runs {@code operation} against this conversation's EXISTING page on the Playwright executor
     * thread (one hop, one action timeout) - the single seam the recipe replayer uses for the page
     * reads/actions the five {@code browser_*} entry points above do not cover (current URL,
     * element presence, select, wait). Requires a session ({@code browser_navigate} creates it),
     * exactly like click/type.
     */
    <T> T withSessionPage(
            String tenantId,
            UUID agentId,
            String sessionId,
            java.util.function.Function<Page, T> operation) {
        String key = sessionKey(tenantId, agentId, sessionId);
        return run(
                actionTimeout(),
                () -> {
                    Page page = requireSession(key).page();
                    T result = operation.apply(page);
                    assertStillPublic(key, page); // SEC-009, same post-action check as click/type
                    lifecycles.checkpoint(key);
                    return result;
                });
    }

    // ---- human takeover seams (see ManagerBrowserTakeoverDriver)
    // -----------------------------------------

    /**
     * Runs {@code operation} on the Playwright thread against an EXISTING session on behalf of the
     * PERSON driving it (live view, input relay, hand-back read). Deliberately does not consult the
     * takeover gate - that gate is what keeps the AGENT out - and never creates a session.
     */
    <T> T runForUser(
            String key,
            boolean touch,
            java.util.function.Function<BrowserUseSession, T> operation) {
        return run(
                actionTimeout(),
                () -> {
                    BrowserUseSession session = sessions.get(key);
                    if (session == null) {
                        throw new BrowserUseException("the browser session is no longer open");
                    }
                    if (touch) {
                        session.touch();
                    }
                    return operation.apply(session);
                });
    }

    /** The domain policy (INACTIVE when no v2 settings bean). */
    BrowserDomainPolicy takeoverDomainPolicy() {
        return domainPolicy();
    }

    /**
     * First non-null answer of the lifecycle observers on whether a saved profile covers {@code
     * host}; null = no profile.
     */
    Boolean profileAllowsHost(String key, String host) {
        return lifecycles.profileAllowsHost(key, host);
    }

    /** Closes a session from outside the executor (takeover expiry); best effort. */
    void closeSessionAsync(String key) {
        if (!enabled) {
            return;
        }
        runner.submit(
                () -> {
                    closeSessionQuietly(key);
                    return null;
                });
    }

    /** The takeover service when it is switched on, else null. */
    BrowserTakeoverService activeTakeover() {
        BrowserTakeoverService service = takeover;
        return service != null && service.isActive() ? service : null;
    }

    // ---- executor-thread internals -----------------------------------------

    private BrowserUseSession getOrCreateSession(
            String tenantId, UUID agentId, String key, String owner) {
        BrowserUseSession existing = sessions.get(key);
        if (existing != null) {
            existing.touch();
            return existing;
        }
        long tenantCount =
                sessions.values().stream().filter(s -> s.tenantId().equals(tenantId)).count();
        BrowserSessionExpiry.checkCapacity(
                tenantCount, sessions.size(), maxSessionsPerTenant, maxTotalSessions);
        BrowserUseSession session = sessionFactory.newSession(tenantId, agentId, key);
        sessions.put(key, session);
        BrowserTakeoverService service = takeover;
        if (service != null) {
            service.onSessionCreated(key, tenantId, owner, agentId);
        }
        return session;
    }

    private BrowserUseSession requireSession(String key) {
        gateAgent(key);
        BrowserUseSession session = sessions.get(key);
        if (session == null) {
            throw new BrowserUseException(
                    "no active browser session for this conversation yet - call browser_navigate first");
        }
        session.touch();
        return session;
    }

    /**
     * SEC-009 post-action check: the session is closed and the action fails when a redirect/frame
     * navigation tainted it or the page URL or any frame URL is not public. Runs on the executor
     * thread.
     */
    private void assertStillPublic(String key, Page page) {
        if (sessionFactory.reachedNonPublic(key, page)) {
            log.warn("browser-use session reached a non-public address; closing it");
            closeSessionQuietly(key);
            throw new BrowserUseException(
                    "the page was redirected to an address inside the platform's own network, so the browser session was closed");
        }
    }

    static String sessionKey(String tenantId, UUID agentId, String sessionId) {
        return tenantId + "|" + agentId + "|" + (sessionId == null ? "" : sessionId);
    }

    private Duration actionTimeout() {
        // A little headroom over the Playwright-side action/navigation timeout,
        // so a Playwright TimeoutException surfaces (with its own clear
        // message) before this Future.get() timeout would.
        return Duration.ofMillis(Math.max(navTimeoutMs, actionTimeoutMs) + 5_000L);
    }

    private <T> T run(Duration timeout, java.util.concurrent.Callable<T> operation) {
        if (!enabled) {
            throw new BrowserUseException("browser-use is not enabled on this platform");
        }
        return runner.run(timeout, operation);
    }

    // ---- idle/lifetime reaper -----------------------------------------------

    /**
     * Closes sessions past {@link #idleTimeoutSeconds} of inactivity or {@link #maxLifetimeSeconds}
     * of total age. Runs every minute; a no-op (fast, cheap) when {@link #sessions} is empty, which
     * is the common case since this is a fallback tool, not a steady-traffic one.
     */
    @AllowUnlockedSchedule(
            reason =
                    "Closes this JVM's own browser sessions (the local sessions map pinned to the executor thread); every replica must reap its own.")
    @Scheduled(fixedDelay = 60_000, initialDelay = 60_000)
    void sweepExpiredSessions() {
        // The isEmpty() check reads `sessions` from the scheduler thread, not
        // the executor thread - a benign, eventually-consistent read of a map
        // the executor thread mutates. A stale "empty" read just means this
        // run's sweep is skipped and picked up on the next one; it never
        // causes an expired session to be missed for more than a minute.
        if (!enabled || sessions.isEmpty()) {
            return;
        }
        // execute + catch, not submit: a submit()ted task's exception lands in a
        // Future nobody reads, so a failing sweep used to vanish without a log
        // line while expired browser sessions quietly piled up.
        runner.execute(
                () -> {
                    try {
                        List<String> expired =
                                BrowserSessionExpiry.expiredKeys(
                                        sessions.values(),
                                        Instant.now(),
                                        idleTimeoutSeconds,
                                        maxLifetimeSeconds,
                                        key -> {
                                            BrowserTakeoverService holder = takeover;
                                            return holder != null && holder.holdsSession(key);
                                        });
                        for (String key : expired) {
                            closeSessionQuietly(key);
                        }
                        if (!expired.isEmpty()) {
                            log.info(
                                    "browser-use reaper closed {} expired session(s)",
                                    expired.size());
                        }
                    } catch (RuntimeException e) {
                        log.warn("browser-use reaper sweep failed; retrying on the next run", e);
                    }
                });
    }

    private void closeSessionQuietly(String key) {
        sessionFactory.forget(key);
        BrowserUseSession session = sessions.remove(key);
        if (session == null) {
            return;
        }
        BrowserTakeoverService service = takeover;
        if (service != null) {
            service.onSessionClosed(key);
        }
        lifecycles.sessionClosing(session, key);
        try {
            session.close();
        } catch (RuntimeException e) {
            log.debug("browser-use session close failed for {}: {}", key, e.toString());
        }
    }

    @PreDestroy
    void shutdown() {
        runner.shutdown(
                () -> {
                    for (String key : new ArrayList<>(sessions.keySet())) {
                        closeSessionQuietly(key);
                    }
                    playwright.close();
                    return null;
                });
    }

    /** Optional session observers (browser profiles); setter-injected, empty = no-op. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setLifecycles(List<BrowserSessionLifecycle> lifecycles) {
        this.lifecycles.set(lifecycles);
    }

    /**
     * {@code browser_navigate}'s result: where the page ended up (after any redirects) and its
     * title.
     */
    record NavigateResult(String finalUrl, String title) {}
}
