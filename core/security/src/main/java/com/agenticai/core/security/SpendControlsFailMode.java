package com.agenticai.core.security;

import com.agenticai.core.security.GovernanceFailOpenPolicy.Mode;
import org.slf4j.Logger;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Injectable holder for {@code app.governance.spend-controls.fail-mode} ({@code open}, the default, or {@code closed}).
 * Services take it through an optional setter and fall back to {@link #open()} so direct construction (tests) keeps
 * today's behaviour. An unknown value fails startup rather than silently choosing a side.
 */
@Component
public class SpendControlsFailMode {

    private static final SpendControlsFailMode OPEN = new SpendControlsFailMode(Mode.OPEN);

    private final Mode mode;

    @org.springframework.beans.factory.annotation.Autowired
    public SpendControlsFailMode(@Value("${app.governance.spend-controls.fail-mode:open}") String value) {
        this(Mode.parse(value));
    }

    private SpendControlsFailMode(Mode mode) {
        this.mode = mode;
    }

    /** The default instance: fail open, today's behaviour. */
    public static SpendControlsFailMode open() {
        return OPEN;
    }

    public static SpendControlsFailMode closed() {
        return new SpendControlsFailMode(Mode.CLOSED);
    }

    public Mode mode() {
        return mode;
    }

    public boolean isClosed() {
        return mode == Mode.CLOSED;
    }

    /**
     * The control's Redis backend failed on a GATING path (a read or reservation that decides whether a call may run).
     * Open: logs the shared marker and returns, the caller lets the call through. Closed: throws
     * {@link SpendControlsUnavailableException}. Never call this from a post-call recording path: the money is already
     * spent and there is nothing left to refuse.
     */
    public void onGateFailure(String control, Exception cause, Logger log) {
        if (!GovernanceFailOpenPolicy.allowOnBackendFailure(mode, control, cause, log)) {
            throw new SpendControlsUnavailableException(control, cause);
        }
    }
}
