package com.agenticai.core.tenancy.demo;

/**
 * The no-signup sandbox could not be started - switched off by a platform
 * admin, this device already holds its allowance of live sandboxes, or
 * provisioning genuinely failed.
 *
 * <p>Deliberately ONE exception type covering all three: the visitor-facing
 * message must not let an anonymous caller distinguish "you tripped the
 * per-IP cap" from "the feature is off" from "the database is unhappy",
 * since that difference is only useful to somebody probing the limits.
 *
 * <p>Handled in {@code DemoSessionController} directly (a 503 with the
 * message) rather than in {@code GlobalExceptionHandler} - the demo surface
 * owns its own error shapes precisely because its whole contract is "never
 * show this visitor a raw error", and keeping that promise in one small
 * controller is easier to verify than spreading it across the shared
 * handler.
 */
public class DemoSandboxUnavailableException extends RuntimeException {

    public DemoSandboxUnavailableException(String message) {
        super(message);
    }
}
