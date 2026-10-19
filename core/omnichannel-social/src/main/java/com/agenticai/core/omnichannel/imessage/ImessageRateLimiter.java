package com.agenticai.core.omnichannel.imessage;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Sliding one-minute windows that keep an iMessage conversation from running away: a chat that
 * floods the bridge (or a reply loop between two bots) costs LLM tokens on every message, and the
 * Mac that sends the replies is a single consumer-grade Messages account that Apple may throttle
 * or lock when it bursts.
 *
 * <ul>
 *   <li><b>Inbound</b>, per chat ({@code app.imessage.rate-limit.inbound-per-minute}, default 20):
 *       an excess message is dropped before any session write or LLM turn.
 *   <li><b>Outbound</b>, per tenant ({@code app.imessage.rate-limit.outbound-per-minute}, default
 *       30): an excess send fails with a retryable error, so the approval queue retries it later.
 * </ul>
 *
 * In memory and per replica, like {@code InboundChannelExecutor}'s caps; a value of 0 disables a
 * window. The key space is bounded: when the map grows past a few thousand entries, idle keys are
 * dropped.
 */
@Component
public class ImessageRateLimiter {

    private static final long WINDOW_MS = 60_000;
    private static final int PRUNE_THRESHOLD = 4_096;

    private final int inboundPerMinute;
    private final int outboundPerMinute;
    private final LongSupplier clockMs;
    private final Map<String, Deque<Long>> inbound = new ConcurrentHashMap<>();
    private final Map<String, Deque<Long>> outbound = new ConcurrentHashMap<>();

    @Autowired
    public ImessageRateLimiter(
            @Value("${app.imessage.rate-limit.inbound-per-minute:20}") int inboundPerMinute,
            @Value("${app.imessage.rate-limit.outbound-per-minute:30}") int outboundPerMinute) {
        this(inboundPerMinute, outboundPerMinute, System::currentTimeMillis);
    }

    ImessageRateLimiter(int inboundPerMinute, int outboundPerMinute, LongSupplier clockMs) {
        this.inboundPerMinute = inboundPerMinute;
        this.outboundPerMinute = outboundPerMinute;
        this.clockMs = clockMs;
    }

    /** @return true when this inbound message from {@code chatKey} is within the limit. */
    public boolean tryInbound(String chatKey) {
        return tryAcquire(inbound, chatKey, inboundPerMinute);
    }

    /** @return true when this send for {@code tenantKey} is within the limit. */
    public boolean tryOutbound(String tenantKey) {
        return tryAcquire(outbound, tenantKey, outboundPerMinute);
    }

    private boolean tryAcquire(Map<String, Deque<Long>> windows, String key, int limit) {
        if (limit <= 0) {
            return true;
        }
        long now = clockMs.getAsLong();
        if (windows.size() > PRUNE_THRESHOLD) {
            windows.entrySet().removeIf(e -> isIdle(e.getValue(), now));
        }
        Deque<Long> window = windows.computeIfAbsent(key == null ? "" : key, k -> new ArrayDeque<>());
        synchronized (window) {
            while (!window.isEmpty() && now - window.peekFirst() >= WINDOW_MS) {
                window.pollFirst();
            }
            if (window.size() >= limit) {
                return false;
            }
            window.addLast(now);
            return true;
        }
    }

    private static boolean isIdle(Deque<Long> window, long now) {
        synchronized (window) {
            return window.isEmpty() || now - window.peekLast() >= WINDOW_MS;
        }
    }
}
