package com.agenticai.core.agents.tools.browseruse;

import com.agenticai.core.agents.tools.browseruse.BrowserTakeoverDtos.Begin;
import com.agenticai.core.agents.tools.browseruse.BrowserTakeoverDtos.FrameEvent;
import com.agenticai.core.agents.tools.browseruse.BrowserTakeoverDtos.InputEvent;
import com.agenticai.core.agents.tools.browseruse.BrowserTakeoverDtos.TokenRefresh;
import com.agenticai.core.agents.tools.browseruse.BrowserTakeoverDtos.View;
import com.agenticai.core.audit.AuditLogService;
import com.agenticai.core.tenancy.AllowUnlockedSchedule;
import jakarta.annotation.PreDestroy;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/**
 * Human takeover of a browser session: the registry of per-session {@link BrowserTakeoverControl}s,
 * and every rule about who may do what while the person (not the agent) holds the page. See {@link
 * BrowserTakeoverState} for the state machine.
 *
 * <h2>What guarantees "the agent cannot act while the person drives"</h2>
 *
 * {@link BrowserUseSessionManager} calls {@link #requireAgentControl} at the start of EVERY
 * agent-facing operation (navigate, click, type, read, screenshot, every v2 tool, recipe replay)
 * and refuses unless the state is AGENT_CONTROL. The person's own operations go through separate
 * driver methods that do not consult it.
 *
 * <h2>Authorisation of the person's calls</h2>
 *
 * Every call re-checks, against the live control: the caller's tenant and user equal the session's
 * owner (a mismatch answers 404 - existence is not revealed), and for the stream and input calls
 * also a valid, unexpired, HMAC-signed takeover token minted for THIS takeover, THIS epoch, THIS
 * tenant and user ({@link BrowserTakeoverToken}). Input is rate limited per session (token bucket)
 * and size capped; the idle and hard-duration clocks end a takeover that is abandoned.
 *
 * <h2>Secrets and privacy</h2>
 *
 * What the person types is relayed to the page and nothing else: it is not logged, not audited, not
 * put in any result. Typed runs long enough to scrub safely are kept as in-memory candidates and
 * registered with the session's redaction set at hand-back, so the page text the agent reads
 * afterwards cannot echo them. The agent only ever learns that the person finished, plus that
 * redacted page text. Audit entries (start / end) carry ids, the reason KIND, the end cause and
 * counts - never content.
 *
 * <p>Single constructor on purpose (see {@code InjectableConstructorTest}). The driver is set by
 * the session manager (which is the driver), so the two beans do not depend on each other at
 * construction.
 */
@Component
public class BrowserTakeoverService {

    private static final Logger log = LoggerFactory.getLogger(BrowserTakeoverService.class);

    static final String AUDIT_REQUESTED = "BROWSER_TAKEOVER_REQUESTED";
    static final String AUDIT_STARTED = "BROWSER_TAKEOVER_STARTED";
    static final String AUDIT_ENDED = "BROWSER_TAKEOVER_ENDED";

    private final BrowserTakeoverSettings settings;
    private final BrowserTakeoverAudit auditTrail;
    private final BrowserTakeoverToken tokens;

    private final BrowserTakeoverRegistry registry = new BrowserTakeoverRegistry();
    private final BrowserTakeoverInput inputRelay;
    private final BrowserTakeoverEnding ending;
    private final AtomicInteger openStreams = new AtomicInteger();
    private final AtomicLong frameSeq = new AtomicLong();
    private volatile BrowserTakeoverDriver driver;
    private volatile Supplier<Instant> clock = Instant::now;

    public BrowserTakeoverService(
            BrowserTakeoverSettings settings, ObjectProvider<AuditLogService> audit) {
        this.settings = settings;
        this.auditTrail = new BrowserTakeoverAudit(audit, () -> clock.get());
        this.tokens = new BrowserTakeoverToken(settings.tokenSecret());
        this.inputRelay = new BrowserTakeoverInput(settings, this::driver);
        this.ending = new BrowserTakeoverEnding(settings, registry, auditTrail, () -> driver);
    }

    void setDriver(BrowserTakeoverDriver driver) {
        this.driver = driver;
    }

    /** Test seam: a controllable clock. */
    void setClock(Supplier<Instant> clock) {
        this.clock = clock;
    }

    BrowserTakeoverSettings settings() {
        return settings;
    }

    /**
     * True when takeover is switched on (and browser v2 with it). Everything below is a no-op / 404
     * when false.
     */
    public boolean isActive() {
        return settings.isActive();
    }

    @PreDestroy
    void shutdown() {
        auditTrail.shutdown();
    }

    // ---- hooks used by the session manager
    // ----------------------------------------------------------------

    /**
     * A browser session was created. {@code ownerUserId} is the subject of the call that created it
     * (null for an unattended run).
     */
    void onSessionCreated(String sessionKey, String tenantId, String ownerUserId, UUID agentId) {
        if (!isActive()) {
            return;
        }
        BrowserTakeoverControl control =
                new BrowserTakeoverControl(
                        sessionKey,
                        tenantId,
                        ownerUserId,
                        agentId,
                        now(),
                        settings.inputPerSecond(),
                        settings.inputBurst());
        registry.register(control);
    }

    /** The session ended (reaper, shutdown, our own expiry). Ends any takeover still open on it. */
    void onSessionClosed(String sessionKey) {
        BrowserTakeoverControl control = registry.removeSession(sessionKey);
        if (control == null) {
            return;
        }
        synchronized (control) {
            if (!control.state().isTerminal()) {
                boolean wasUserHeld = control.state() != BrowserTakeoverState.AGENT_CONTROL;
                control.cancel("session_closed");
                if (wasUserHeld) {
                    auditTrail.record(AUDIT_ENDED, control);
                }
            }
        }
    }

    /**
     * True while the person (or a pending request for them) holds the session: the idle reaper must
     * not close it.
     */
    boolean holdsSession(String sessionKey) {
        BrowserTakeoverControl control = registry.bySessionKey(sessionKey);
        return control != null
                && control.state() != BrowserTakeoverState.AGENT_CONTROL
                && !control.state().isTerminal();
    }

    /**
     * Refuses an agent browser action unless the agent is in control. The message is model-facing:
     * it names the state, says not to retry, and says how to find out when control returns.
     */
    void requireAgentControl(String sessionKey) {
        if (!isActive()) {
            return;
        }
        BrowserTakeoverControl control = registry.bySessionKey(sessionKey);
        if (control == null) {
            return;
        }
        BrowserTakeoverState state = control.state();
        if (state == BrowserTakeoverState.AGENT_CONTROL) {
            return;
        }
        throw new BrowserUseException(BrowserTakeoverText.agentRefusal(state));
    }

    // ---- the agent's tool
    // ---------------------------------------------------------------------------------

    /** What {@code browser_request_user} returns to the model. */
    record AgentOutcome(String state, String message) {}

    /**
     * The agent asks for the person to take over (captcha, 2FA, login). Parks the agent: from here
     * every browser tool is refused until the person hands control back. Idempotent while already
     * requested.
     */
    AgentOutcome agentRequest(String sessionKey, String kind, String reason) {
        requireActive();
        BrowserTakeoverControl control = registry.bySessionKey(sessionKey);
        if (control == null) {
            throw new BrowserUseException(
                    "no active browser session for this conversation yet - call browser_navigate first");
        }
        if (control.ownerUserId() == null) {
            throw new BrowserUseException(
                    "no interactive user is attached to this browser session (an unattended run), so there is "
                            + "nobody to hand control to - report that you are blocked and stop.");
        }
        BrowserTakeoverState state = control.state();
        if (state == BrowserTakeoverState.PAUSED_FOR_USER
                || state == BrowserTakeoverState.USER_CONTROL) {
            return new AgentOutcome(state.name(), BrowserTakeoverText.waitingMessage());
        }
        if (state != BrowserTakeoverState.AGENT_CONTROL) {
            throw new BrowserUseException(
                    "the browser session is not available for a takeover request right now ("
                            + state
                            + ")");
        }
        String host = checkSite(control);
        String cleanKind = BrowserTakeoverText.normalizeKind(kind);
        synchronized (control) {
            if (control.state() != BrowserTakeoverState.AGENT_CONTROL) {
                return new AgentOutcome(control.state().name(), BrowserTakeoverText.waitingMessage());
            }
            control.pause(
                    BrowserTakeoverControl.Origin.AGENT,
                    cleanKind,
                    sanitizeReason(reason),
                    host,
                    now());
        }
        log.info(
                "browser takeover requested (kind {}, session {})",
                cleanKind,
                control.takeoverId());
        auditTrail.record(AUDIT_REQUESTED, control);
        return new AgentOutcome(BrowserTakeoverState.PAUSED_FOR_USER.name(), BrowserTakeoverText.waitingMessage());
    }

    /** The agent asks where things stand; hands out the redacted resume note exactly once. */
    AgentOutcome agentStatus(String sessionKey) {
        requireActive();
        BrowserTakeoverControl control = registry.bySessionKey(sessionKey);
        if (control == null) {
            return new AgentOutcome(
                    "NO_SESSION",
                    "There is no active browser session (it may have expired) - call browser_navigate to start a new one.");
        }
        BrowserTakeoverState state = control.state();
        if (state == BrowserTakeoverState.AGENT_CONTROL) {
            String note = control.consumeResumeNote();
            return new AgentOutcome(
                    state.name(),
                    note != null
                            ? note
                            : "You are in control of the browser; no takeover is pending.");
        }
        return new AgentOutcome(
                state.name(),
                state == BrowserTakeoverState.RESUMING
                        ? "Control is being handed back to you - ask again in a few seconds."
                        : BrowserTakeoverText.waitingMessage());
    }

    // ---- the person's calls
    // -------------------------------------------------------------------------------

    /**
     * The takeover for this agent + chat session as its owner sees it, or null when there is none
     * (or it is someone else's).
     */
    View status(String tenantId, String userId, UUID agentId, String sessionId) {
        requireActiveOrNotFound();
        BrowserTakeoverControl control =
                registry.ownedByKey(tenantId, userId, agentId, sessionId);
        if (control == null || control.state() == BrowserTakeoverState.AGENT_CONTROL) {
            return null;
        }
        return view(control);
    }

    /** The person pauses their own session so they can step in. */
    View pause(
            String tenantId,
            String userId,
            UUID agentId,
            String sessionId,
            String kind,
            String reason) {
        requireActiveOrNotFound();
        BrowserTakeoverControl control =
                registry.ownedByKey(tenantId, userId, agentId, sessionId);
        if (control == null) {
            throw notFound();
        }
        String host;
        try {
            host = checkSite(control);
        } catch (BrowserUseException e) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, e.getMessage());
        }
        synchronized (control) {
            if (control.state() == BrowserTakeoverState.AGENT_CONTROL) {
                control.pause(
                        BrowserTakeoverControl.Origin.USER,
                        BrowserTakeoverText.normalizeKind(kind),
                        sanitizeReason(reason),
                        host,
                        now());
            } else if (!control.state().userHasThePage()) {
                throw new ResponseStatusException(
                        HttpStatus.CONFLICT, "This browser session cannot be paused right now.");
            }
        }
        auditTrail.record(AUDIT_REQUESTED, control);
        return view(control);
    }

    /** PAUSED_FOR_USER -> USER_CONTROL; mints the first token. */
    Begin begin(String tenantId, String userId, UUID takeoverId) {
        requireActiveOrNotFound();
        BrowserTakeoverControl control = registry.ownedById(tenantId, userId, takeoverId);
        synchronized (control) {
            if (control.state() == BrowserTakeoverState.USER_CONTROL) {
                // Re-opening the panel (a reload) re-issues a token for the same episode.
                return beginResponse(control);
            }
            if (control.state() != BrowserTakeoverState.PAUSED_FOR_USER) {
                throw new ResponseStatusException(
                        HttpStatus.CONFLICT,
                        "There is no takeover waiting for you on this session.");
            }
            control.beginUserControl(now());
        }
        log.info("browser takeover started (session {})", control.takeoverId());
        auditTrail.record(AUDIT_STARTED, control);
        return beginResponse(control);
    }

    private Begin beginResponse(BrowserTakeoverControl control) {
        TokenRefresh token = mintToken(control);
        return new Begin(
                view(control),
                token.token(),
                token.tokenExpiresInSeconds(),
                settings.fps(),
                settings.maxDimension(),
                settings.maxTypeChars(),
                settings.inputPerSecond());
    }

    TokenRefresh refreshToken(String tenantId, String userId, UUID takeoverId) {
        requireActiveOrNotFound();
        BrowserTakeoverControl control = registry.ownedById(tenantId, userId, takeoverId);
        if (control.state() != BrowserTakeoverState.USER_CONTROL) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT, "You are not in control of this browser session.");
        }
        return mintToken(control);
    }

    private TokenRefresh mintToken(BrowserTakeoverControl control) {
        Instant expires = now().plusSeconds(settings.tokenTtlSeconds());
        String token =
                tokens.issue(
                        control.takeoverId(),
                        control.epoch(),
                        control.tenantId(),
                        control.ownerUserId(),
                        expires);
        return new TokenRefresh(token, settings.tokenTtlSeconds());
    }

    /**
     * Relays one input event to the page. Order matters: owner, then token, then state, then rate
     * limit, then validation - so an unauthenticated caller learns nothing and cannot burn the
     * owner's rate budget.
     */
    void input(String tenantId, String userId, UUID takeoverId, String token, InputEvent event) {
        requireActiveOrNotFound();
        BrowserTakeoverControl control = authorize(tenantId, userId, takeoverId, token);
        if (event == null || event.type() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Missing input type.");
        }
        Instant now = now();
        if (!control.admitInput(now)) {
            throw new ResponseStatusException(
                    HttpStatus.TOO_MANY_REQUESTS, "Too many input events - slow down.");
        }
        inputRelay.relay(control, event);
    }

    /** Frame-pixel coordinate to page coordinate: proportional, clamped inside the viewport. */
    static int mapCoordinate(int framePixel, int frameSize, int viewportSize) {
        return BrowserTakeoverInput.mapCoordinate(framePixel, frameSize, viewportSize);
    }

    /**
     * Authorises a stream connect (owner + valid token + USER_CONTROL), reserves a stream slot and
     * returns the epoch the stream is bound to. The caller MUST {@link #releaseStream} when the
     * stream ends. The token gates the CONNECT; once open the stream stays valid exactly as long as
     * the same episode is still the person's (a stream does not die when the short token expires -
     * the client renews the token for input calls).
     */
    int openStream(String tenantId, String userId, UUID takeoverId, String token) {
        requireActiveOrNotFound();
        BrowserTakeoverControl control = authorize(tenantId, userId, takeoverId, token);
        if (openStreams.incrementAndGet() > settings.maxStreams()) {
            openStreams.decrementAndGet();
            throw new ResponseStatusException(
                    HttpStatus.TOO_MANY_REQUESTS,
                    "Too many live views are open on this server - try again shortly.");
        }
        return control.epoch();
    }

    void releaseStream() {
        openStreams.updateAndGet(n -> Math.max(0, n - 1));
    }

    /**
     * True while a stream bound to {@code epoch} should keep running: same owner, same episode,
     * still USER_CONTROL.
     */
    boolean streamStillValid(String tenantId, String userId, UUID takeoverId, int epoch) {
        if (!isActive()) {
            return false;
        }
        BrowserTakeoverControl control = registry.byTakeoverId(takeoverId);
        return control != null
                && control.isOwnedBy(tenantId, userId)
                && control.state() == BrowserTakeoverState.USER_CONTROL
                && control.epoch() == epoch;
    }

    /** One frame for an open stream. Frames are not "activity": they never reset the idle clock. */
    FrameEvent captureFrame(String tenantId, String userId, UUID takeoverId, int epoch) {
        if (!streamStillValid(tenantId, userId, takeoverId, epoch)) {
            throw notFound();
        }
        BrowserTakeoverControl control = registry.byTakeoverId(takeoverId);
        BrowserTakeoverDriver.Frame frame;
        try {
            frame =
                    driver().frame(
                                    control.sessionKey(),
                                    settings.maxDimension(),
                                    settings.jpegQuality());
        } catch (BrowserUseException e) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT, "The browser could not produce a frame.");
        }
        Instant now = now();
        return new FrameEvent(
                frameSeq.incrementAndGet(),
                "image/jpeg",
                Base64.getEncoder().encodeToString(frame.jpeg()),
                frame.frameWidth(),
                frame.frameHeight(),
                control.state().name(),
                control.idleRemainingSeconds(now, settings.idleTimeoutSeconds()),
                control.maxRemainingSeconds(now, settings.maxDurationSeconds()));
    }

    /** The person gives the page back (also the way to decline a request without acting). */
    View handBack(String tenantId, String userId, UUID takeoverId) {
        requireActiveOrNotFound();
        BrowserTakeoverControl control = registry.ownedById(tenantId, userId, takeoverId);
        synchronized (control) {
            if (control.state() == BrowserTakeoverState.AGENT_CONTROL) {
                return view(control);
            }
            if (!control.state().userHasThePage()) {
                throw new ResponseStatusException(
                        HttpStatus.CONFLICT, "This browser session is not waiting for you.");
            }
            control.beginResume("user");
        }
        ending.completeResume(
                control,
                "The user finished the requested step in the browser and handed control back to you.");
        return view(control);
    }

    /**
     * The person abandons the task: the browser session is closed and the agent will find it gone.
     */
    View cancel(String tenantId, String userId, UUID takeoverId) {
        requireActiveOrNotFound();
        BrowserTakeoverControl control = registry.ownedById(tenantId, userId, takeoverId);
        synchronized (control) {
            if (control.state().isTerminal()) {
                return view(control);
            }
            control.cancel("user_cancelled");
        }
        ending.finishTerminal(control);
        return view(control);
    }

    // ---- reaper
    // -------------------------------------------------------------------------------------------

    /**
     * Runs the idle / hard-duration / wait clocks. Cheap when nothing is held; every few seconds.
     */
    @AllowUnlockedSchedule(
            reason =
                    "Acts on this JVM's own in-memory browser sessions; every replica must run its own sweep.")
    @Scheduled(fixedDelay = 5_000, initialDelay = 5_000)
    void sweep() {
        if (!isActive() || registry.isEmpty()) {
            return;
        }
        reap(now());
    }

    void reap(Instant now) {
        ending.reap(now);
    }

    static String buildNote(String lead, BrowserTakeoverDriver.ResumeSnapshot snapshot) {
        return BrowserTakeoverText.buildNote(lead, snapshot);
    }

    // ---- authorisation helpers
    // ----------------------------------------------------------------------------

    private BrowserTakeoverControl authorize(
            String tenantId, String userId, UUID takeoverId, String token) {
        BrowserTakeoverControl control = registry.ownedById(tenantId, userId, takeoverId);
        BrowserTakeoverToken.Claims claims = tokens.verify(token, now());
        if (claims == null
                || !claims.takeoverId().equals(control.takeoverId())
                || !tenantId.equals(claims.tenantId())
                || !userId.equals(claims.userId())
                || claims.epoch() != control.epoch()) {
            throw new ResponseStatusException(
                    HttpStatus.FORBIDDEN, "The takeover token is missing, expired or not yours.");
        }
        if (control.state() != BrowserTakeoverState.USER_CONTROL) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT, "You are not in control of this browser session.");
        }
        return control;
    }

    /**
     * Site rule (domain policy, profile scope, explicit allow-list). Returns the host; throws
     * {@link BrowserUseException} with the refusal.
     */
    private String checkSite(BrowserTakeoverControl control) {
        BrowserTakeoverDriver d = driver();
        String url = d.currentUrl(control.sessionKey());
        String refusal = d.siteRefusal(control.sessionKey(), control.agentId(), url);
        if (refusal != null) {
            throw new BrowserUseException(refusal);
        }
        String host = BrowserDomainPolicy.hostOrNull(url);
        return host == null ? "" : host;
    }

    private View view(BrowserTakeoverControl control) {
        Instant now = now();
        BrowserTakeoverControl.Origin origin = control.origin();
        return new View(
                control.takeoverId().toString(),
                control.state().name(),
                origin == null ? null : origin.name().toLowerCase(Locale.ROOT),
                control.kind(),
                control.reason(),
                control.host(),
                control.idleRemainingSeconds(now, settings.idleTimeoutSeconds()),
                control.maxRemainingSeconds(now, settings.maxDurationSeconds()),
                control.pausedRemainingSeconds(now, settings.pausedWaitSeconds()));
    }

    // ---- small utilities
    // ----------------------------------------------------------------------------------

    private BrowserTakeoverDriver driver() {
        BrowserTakeoverDriver d = driver;
        if (d == null) {
            throw new BrowserUseException(
                    "browser takeover is not wired to a browser in this deployment");
        }
        return d;
    }

    private Instant now() {
        return clock.get();
    }

    private void requireActive() {
        if (!isActive()) {
            throw new BrowserUseException("browser takeover is not enabled on this platform");
        }
    }

    private void requireActiveOrNotFound() {
        if (!isActive()) {
            throw notFound();
        }
    }

    private static ResponseStatusException notFound() {
        return BrowserTakeoverRegistry.notFound();
    }

    /** The reason shown to the person: control characters removed, whitespace collapsed, capped. */
    String sanitizeReason(String reason) {
        return BrowserTakeoverText.sanitizeReason(reason, settings.reasonMaxChars());
    }

    // ---- test visibility
    // ----------------------------------------------------------------------------------

    /** The control for a session key (tests and the tool wiring only). */
    BrowserTakeoverControl controlFor(String sessionKey) {
        return registry.bySessionKey(sessionKey);
    }

    /** The audit actions for tests that want to assert on the strings. */
    static List<String> auditActions() {
        return List.of(AUDIT_REQUESTED, AUDIT_STARTED, AUDIT_ENDED);
    }
}
