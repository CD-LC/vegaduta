package com.agenticai.core.omnichannel.whatsappvoice;

import java.time.Clock;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * Per-sender sliding-window limiter for inbound voice notes (a voice note costs a media download and
 * a paid speech call, so one sender must not be able to run up a tenant's bill). In memory and per
 * instance on purpose - core runs one replica and a restart merely forgets recent history; the
 * tenant-wide ceiling is the DB-backed Sarvam call-log count in {@link WhatsAppVoiceNoteService}.
 *
 * <p>To avoid a reply storm the limiter also says whether the sender has already been TOLD about the
 * limit in this window: only the first denial ({@link Decision#DENY_FIRST}) earns a text notice,
 * later ones ({@link Decision#DENY_REPEAT}) are dropped silently.
 *
 * <p>Keys must be hashed sender ids, never phone numbers (they live in memory only, but this class
 * never needs the raw value).
 */
final class VoiceNoteRateLimiter {

    enum Decision { ALLOW, DENY_FIRST, DENY_REPEAT }

    private static final long WINDOW_MILLIS = 60L * 60L * 1000L;
    private static final int PRUNE_THRESHOLD = 2_000;

    private final int maxPerWindow;
    private final Clock clock;
    private final Map<String, ArrayDeque<Long>> hits = new HashMap<>();
    private final Map<String, Long> notifiedAt = new HashMap<>();

    VoiceNoteRateLimiter(int maxPerHour, Clock clock) {
        this.maxPerWindow = maxPerHour;
        this.clock = clock;
    }

    synchronized Decision tryAcquire(String senderKey) {
        if (maxPerWindow <= 0) {
            return Decision.ALLOW;
        }
        long now = clock.millis();
        if (hits.size() > PRUNE_THRESHOLD) {
            prune(now);
        }
        ArrayDeque<Long> window = hits.computeIfAbsent(senderKey, k -> new ArrayDeque<>());
        while (!window.isEmpty() && now - window.peekFirst() >= WINDOW_MILLIS) {
            window.pollFirst();
        }
        if (window.size() < maxPerWindow) {
            window.addLast(now);
            notifiedAt.remove(senderKey);
            return Decision.ALLOW;
        }
        Long told = notifiedAt.get(senderKey);
        if (told != null && now - told < WINDOW_MILLIS) {
            return Decision.DENY_REPEAT;
        }
        notifiedAt.put(senderKey, now);
        return Decision.DENY_FIRST;
    }

    private void prune(long now) {
        Iterator<Map.Entry<String, ArrayDeque<Long>>> it = hits.entrySet().iterator();
        while (it.hasNext()) {
            ArrayDeque<Long> window = it.next().getValue();
            while (!window.isEmpty() && now - window.peekFirst() >= WINDOW_MILLIS) {
                window.pollFirst();
            }
            if (window.isEmpty()) {
                it.remove();
            }
        }
        notifiedAt.values().removeIf(t -> now - t >= WINDOW_MILLIS);
    }
}
