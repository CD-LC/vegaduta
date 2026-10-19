package com.agenticai.core.sdlc;

/**
 * Any failed call to E2B's REST API (create/list/kill sandbox) - carries the
 * real upstream {@code error.code}/{@code error.message} E2B returns (per
 * its OpenAPI spec's {@code Error} schema: {@code {code: int, message:
 * string}}) rather than a generic message, same "translate the upstream
 * error, don't swallow it" discipline {@code SarvamApiException}/{@code
 * UpstreamProviderException} already apply to every other outbound provider
 * call in this codebase.
 */
public class E2bApiException extends RuntimeException {

    private final int upstreamStatus;

    public E2bApiException(int upstreamStatus, String message) {
        super(message);
        this.upstreamStatus = upstreamStatus;
    }

    public int getUpstreamStatus() {
        return upstreamStatus;
    }
}
