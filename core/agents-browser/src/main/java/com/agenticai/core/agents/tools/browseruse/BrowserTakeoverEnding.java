package com.agenticai.core.agents.tools.browseruse;

import java.time.Instant;
import java.util.List;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * How a takeover ends: the hand-back to the agent (read the page back with what the person typed
 * scrubbed, then let the agent in), the terminal end (session closed), and the idle / hard-duration
 * / wait clocks that end an abandoned takeover. Split out of {@link BrowserTakeoverService}, which
 * keeps the {@code @Scheduled} sweep and calls {@link #reap}. Logs under the service's logger.
 */
final class BrowserTakeoverEnding {

    private static final Logger log = LoggerFactory.getLogger(BrowserTakeoverService.class);

    private final BrowserTakeoverSettings settings;
    private final BrowserTakeoverRegistry registry;
    private final BrowserTakeoverAudit auditTrail;
    // The wired driver, or null when none is (a deployment without the browser).
    private final Supplier<BrowserTakeoverDriver> driver;

    BrowserTakeoverEnding(
            BrowserTakeoverSettings settings,
            BrowserTakeoverRegistry registry,
            BrowserTakeoverAudit auditTrail,
            Supplier<BrowserTakeoverDriver> driver) {
        this.settings = settings;
        this.registry = registry;
        this.auditTrail = auditTrail;
        this.driver = driver;
    }

    void reap(Instant now) {
        for (BrowserTakeoverControl control : registry.snapshot()) {
            try {
                reapOne(control, now);
            } catch (RuntimeException e) {
                log.warn(
                        "browser takeover sweep failed for one session: {}",
                        e.getClass().getSimpleName());
            }
        }
    }

    private void reapOne(BrowserTakeoverControl control, Instant now) {
        BrowserTakeoverState state = control.state();
        if (state == BrowserTakeoverState.PAUSED_FOR_USER
                && control.pausedRemainingSeconds(now, settings.pausedWaitSeconds()) == 0) {
            expireAndClose(control, "wait_timeout");
            return;
        }
        if (state != BrowserTakeoverState.USER_CONTROL) {
            return;
        }
        if (control.maxRemainingSeconds(now, settings.maxDurationSeconds()) == 0) {
            expireAndClose(control, "max_duration");
            return;
        }
        if (control.idleRemainingSeconds(now, settings.idleTimeoutSeconds()) == 0) {
            if (settings.idleAction() == BrowserTakeoverSettings.IdleAction.EXPIRE) {
                expireAndClose(control, "idle");
                return;
            }
            synchronized (control) {
                if (control.state() != BrowserTakeoverState.USER_CONTROL) {
                    return;
                }
                control.beginResume("idle");
            }
            completeResume(
                    control,
                    "The user was idle for too long, so control was returned to you automatically (they may not have finished the step).");
        }
    }

    private void expireAndClose(BrowserTakeoverControl control, String by) {
        synchronized (control) {
            if (control.state().isTerminal()) {
                return;
            }
            control.expire(by);
        }
        finishTerminal(control);
    }

    /** Unregisters a terminal takeover, audits its end and closes the browser session. */
    void finishTerminal(BrowserTakeoverControl control) {
        registry.remove(control);
        auditTrail.record(BrowserTakeoverService.AUDIT_ENDED, control);
        try {
            BrowserTakeoverDriver d = driver.get();
            if (d != null) {
                d.closeSession(control.sessionKey());
            }
        } catch (RuntimeException e) {
            log.debug(
                    "browser takeover could not close the session: {}",
                    e.getClass().getSimpleName());
        }
    }

    /**
     * RESUMING -> AGENT_CONTROL: reads the page back (scrubbing what the person typed), builds the
     * note the agent may fetch once, and only then lets the agent in. A failure to read must not
     * strand the session in RESUMING.
     */
    void completeResume(BrowserTakeoverControl control, String lead) {
        String note;
        try {
            List<String> typed = control.drainTypedCandidates();
            BrowserTakeoverDriver d = driver.get();
            if (d == null) {
                throw new BrowserUseException(
                        "browser takeover is not wired to a browser in this deployment");
            }
            BrowserTakeoverDriver.ResumeSnapshot snapshot =
                    d.resume(
                            control.sessionKey(),
                            control.agentId(),
                            typed,
                            settings.resumeTextMaxChars());
            note = BrowserTakeoverText.buildNote(lead, snapshot);
        } catch (RuntimeException e) {
            log.warn(
                    "browser takeover could not read the page back: {}",
                    e.getClass().getSimpleName());
            note =
                    lead
                            + " The page could not be read back - take a browser_snapshot to see where things stand.";
        }
        synchronized (control) {
            if (control.state() == BrowserTakeoverState.RESUMING) {
                control.finishResume(note);
            }
        }
        log.info(
                "browser takeover ended (session {}, by {})",
                control.takeoverId(),
                control.endedBy());
        auditTrail.record(BrowserTakeoverService.AUDIT_ENDED, control);
    }
}
