package com.agenticai.core.omnichannel.session;

/**
 * Thrown by {@link InboundRateLimiter} (via {@code ChannelSessionService#resolveOrCreate}, the one
 * call every inbound channel makes before it does any work) when a sender, or a whole channel of a
 * tenant, is sending faster than the configured window allows. Mapped to HTTP 429 in core/app's
 * {@code ClientRequestExceptionHandler}; the webhook controllers that catch {@code
 * RuntimeException} turn it into their normal quiet 2xx.
 *
 * <p>The message is the friendly text a person can be shown. It never reveals the limit, the
 * window or the count: none of that is the sender's business.
 */
public class InboundRateLimitedException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** What a human on the other end of a chat can be told. */
    public static final String FRIENDLY_MESSAGE =
            "You're sending messages faster than I can keep up with. Please wait a minute and try again.";

    private final long retryAfterSeconds;
    private final boolean channelWide;

    public InboundRateLimitedException(long retryAfterSeconds, boolean channelWide) {
        // No stack trace: a flood is exactly when this is thrown thousands of times, and callers that
        // log the failure (the email poller) would otherwise print a trace per dropped message.
        super(FRIENDLY_MESSAGE, null, false, false);
        this.retryAfterSeconds = Math.max(1, retryAfterSeconds);
        this.channelWide = channelWide;
    }

    public long getRetryAfterSeconds() {
        return retryAfterSeconds;
    }

    /** True when the channel-wide cap tripped rather than this one sender's. */
    public boolean isChannelWide() {
        return channelWide;
    }
}
