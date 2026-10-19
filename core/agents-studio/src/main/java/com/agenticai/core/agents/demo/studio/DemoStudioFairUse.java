package com.agenticai.core.agents.demo.studio;

import com.agenticai.core.agents.demo.studio.DemoStudioService.StudioException;
import com.agenticai.core.tenancy.demo.DemoSession;
import java.time.Duration;
import java.net.InetAddress;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.LongSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * The gateways in front of an "unlimited" sandbox.
 *
 * <p>The Studio has no lifetime allowance any more - a visitor can run as many
 * missions as they like. What stops that being an open tap is speed, not
 * quantity: a burst limit per sandbox and per network address, a daily
 * ceiling per address, a cap on runs in flight, and a cool-down after repeated
 * guardrail hits (someone probing for what the agent will refuse). Every run
 * also has a hard wall-clock cap, enforced where it executes
 * ({@code DemoStudioService}, {@code DemoStudioController}).
 *
 * <p>Deliberately in memory: core runs a single replica (see the platform
 * backlog), the windows are minutes, and losing them on a restart only ever
 * loosens a limit for a few minutes. If core scales out, move these buckets to
 * Redis behind the same {@link #admit} seam.
 *
 * <p>Every admission and every refusal is written to the {@code studio.audit}
 * log with the sandbox and address, which is what makes "your address is
 * recorded" a true sentence on the page.
 */
@Component
public class DemoStudioFairUse {

    private static final Logger audit = LoggerFactory.getLogger("studio.audit");
    private static final Duration MINUTE = Duration.ofMinutes(1);
    private static final Duration DAY = Duration.ofDays(1);

    private final int missionsPerMinute;
    private final int ipMissionsPerMinute;
    private final int ipDailyCeiling;
    private final int maxConcurrent;
    private final int strikeLimit;
    private final Duration strikeWindow;
    private final Duration cooldown;
    private final LongSupplier clock;

    private final Map<UUID, Deque<Long>> sessionHits = new HashMap<>();
    private final Map<String, Deque<Long>> ipMinute = new HashMap<>();
    private final Map<String, Deque<Long>> ipDay = new HashMap<>();
    /** Permits currently held, per sandbox, with the time each was taken so a leaked one expires instead of locking the sandbox out. */
    private final Map<UUID, Map<Permit, Long>> running = new HashMap<>();
    private static final Duration MAX_HOLD = Duration.ofMinutes(15);
    private final Map<UUID, Deque<Long>> strikes = new HashMap<>();
    private final Map<UUID, Long> coolingUntil = new HashMap<>();
    private long lastSweep;

    @Autowired
    public DemoStudioFairUse(
        @Value("${app.demo.studio.missions-per-minute:6}") int missionsPerMinute,
        @Value("${app.demo.studio.ip-missions-per-minute:15}") int ipMissionsPerMinute,
        @Value("${app.demo.studio.ip-daily-ceiling:600}") int ipDailyCeiling,
        @Value("${app.demo.studio.max-concurrent-runs:2}") int maxConcurrent,
        @Value("${app.demo.studio.strike-limit:3}") int strikeLimit,
        @Value("${app.demo.studio.strike-window-minutes:10}") int strikeWindowMinutes,
        @Value("${app.demo.studio.cooldown-minutes:10}") int cooldownMinutes
    ) {
        this(missionsPerMinute, ipMissionsPerMinute, ipDailyCeiling, maxConcurrent, strikeLimit,
            Duration.ofMinutes(strikeWindowMinutes), Duration.ofMinutes(cooldownMinutes), System::currentTimeMillis);
    }

    DemoStudioFairUse(int missionsPerMinute, int ipMissionsPerMinute, int ipDailyCeiling, int maxConcurrent,
                      int strikeLimit, Duration strikeWindow, Duration cooldown, LongSupplier clock) {
        this.missionsPerMinute = missionsPerMinute;
        this.ipMissionsPerMinute = ipMissionsPerMinute;
        this.ipDailyCeiling = ipDailyCeiling;
        this.maxConcurrent = maxConcurrent;
        this.strikeLimit = strikeLimit;
        this.strikeWindow = strikeWindow;
        this.cooldown = cooldown;
        this.clock = clock;
    }

    /** Held for the life of one run; {@link #release()} is idempotent so it is safe from a stream's doFinally. */
    public final class Permit {
        private final UUID sessionId;
        private final AtomicBoolean released = new AtomicBoolean(false);

        private Permit(UUID sessionId) {
            this.sessionId = sessionId;
        }

        public void release() {
            if (released.compareAndSet(false, true)) {
                synchronized (DemoStudioFairUse.this) {
                    Map<Permit, Long> held = running.get(sessionId);
                    if (held != null) {
                        held.remove(this);
                        if (held.isEmpty()) {
                            running.remove(sessionId);
                        }
                    }
                }
            }
        }
    }

    /**
     * True only for an address that can identify one visitor: not loopback, private, link-local, carrier-grade NAT,
     * unique-local or unknown. Behind an ingress that hides client addresses every visitor would otherwise share ONE
     * bucket and a per-address limit would lock everybody out at once - so such addresses are simply not bucketed
     * (the per-sandbox limits still apply), and are never shown back to the visitor.
     */
    public static boolean isPublicAddress(String ip) {
        if (ip == null || ip.isBlank() || ip.length() > 45 || !ip.matches("[0-9a-fA-F:.]+")) {
            return false; // literals only: never hand arbitrary text to a resolver
        }
        try {
            InetAddress a = InetAddress.getByName(ip);
            if (a.isAnyLocalAddress() || a.isLoopbackAddress() || a.isLinkLocalAddress() || a.isSiteLocalAddress() || a.isMulticastAddress()) {
                return false;
            }
            byte[] b = a.getAddress();
            if (b.length == 4 && (b[0] & 0xFF) == 100 && (b[1] & 0xC0) == 64) {
                return false; // 100.64.0.0/10 carrier-grade NAT
            }
            return !(b.length == 16 && (b[0] & 0xFE) == 0xFC); // fc00::/7 unique-local
        } catch (java.net.UnknownHostException e) {
            return false;
        }
    }

    public record FairUseView(
        int missionsPerMinute, int maxConcurrentRuns, int ipDailyCeiling, int usedToday, int usedLastMinute,
        long coolingSeconds, int strikesLeft
    ) {
    }

    /** Admits one run or throws a 429 the page renders as content. */
    public synchronized Permit admit(DemoSession session, String what) {
        long now = clock.getAsLong();
        sweep(now);
        UUID id = session.getId();
        String ip = ipOf(session);
        boolean bucketByIp = isPublicAddress(ip);
        Map<Permit, Long> held = running.computeIfAbsent(id, k -> new HashMap<>());
        held.values().removeIf(t -> now - t > MAX_HOLD.toMillis());

        long cool = coolingSeconds(id, now);
        if (cool > 0) {
            audit.info("studio.audit action=refused reason=cooldown session={} ip={} what={}", id, ip, what);
            throw new StudioException("cooling_down", 429,
                "The studio paused this sandbox for " + human(cool) + " after several requests were refused by the safety rules. "
                    + "Please use it for legitimate work only.");
        }
        if (held.size() >= maxConcurrent) {
            throw new StudioException("rate_limited", 429,
                "You already have " + maxConcurrent + " runs going. Let one finish, or stop it, then start another.");
        }
        long wait = waitSeconds(sessionHits.computeIfAbsent(id, k -> new ArrayDeque<>()), missionsPerMinute, MINUTE, now);
        if (wait > 0) {
            audit.info("studio.audit action=refused reason=session-burst session={} ip={} what={}", id, ip, what);
            throw new StudioException("rate_limited", 429,
                "That's more than " + missionsPerMinute + " runs a minute. Take a breath - try again in " + human(wait) + ".");
        }
        wait = bucketByIp ? waitSeconds(ipMinute.computeIfAbsent(ip, k -> new ArrayDeque<>()), ipMissionsPerMinute, MINUTE, now) : 0;
        if (wait > 0) {
            audit.info("studio.audit action=refused reason=ip-burst session={} ip={} what={}", id, ip, what);
            throw new StudioException("rate_limited", 429,
                "A lot of runs are coming from your network right now. Try again in " + human(wait) + ".");
        }
        wait = bucketByIp ? waitSeconds(ipDay.computeIfAbsent(ip, k -> new ArrayDeque<>()), ipDailyCeiling, DAY, now) : 0;
        if (wait > 0) {
            audit.info("studio.audit action=refused reason=ip-daily session={} ip={} what={}", id, ip, what);
            throw new StudioException("rate_limited", 429,
                "Your network has used the studio's daily fair-use allowance. It resets gradually over the next day, "
                    + "or create a free account to keep going.");
        }
        sessionHits.get(id).addLast(now);
        if (bucketByIp) {
            ipMinute.get(ip).addLast(now);
            ipDay.get(ip).addLast(now);
        }
        Permit permit = new Permit(id);
        held.put(permit, now);
        audit.info("studio.audit action=admit session={} ip={} what={}", id, ip, what);
        return permit;
    }

    /** A guardrail refused something this sandbox asked for. Enough of them in a window and the sandbox cools down. */
    public synchronized void strike(DemoSession session, String reason) {
        long now = clock.getAsLong();
        UUID id = session.getId();
        Deque<Long> hits = strikes.computeIfAbsent(id, k -> new ArrayDeque<>());
        prune(hits, strikeWindow, now);
        hits.addLast(now);
        audit.warn("studio.audit action=strike session={} ip={} reason={} strikes={}", id, ipOf(session), reason, hits.size());
        if (hits.size() >= strikeLimit) {
            coolingUntil.put(id, now + cooldown.toMillis());
            hits.clear();
            audit.warn("studio.audit action=cooldown session={} ip={} minutes={}", id, ipOf(session), cooldown.toMinutes());
        }
    }

    public synchronized FairUseView view(DemoSession session) {
        long now = clock.getAsLong();
        UUID id = session.getId();
        String ip = ipOf(session);
        boolean bucketByIp = isPublicAddress(ip);
        Deque<Long> minute = bucketByIp ? ipMinute.getOrDefault(ip, new ArrayDeque<>()) : new ArrayDeque<>();
        Deque<Long> day = bucketByIp ? ipDay.getOrDefault(ip, new ArrayDeque<>()) : new ArrayDeque<>();
        prune(minute, MINUTE, now);
        prune(day, DAY, now);
        Deque<Long> s = strikes.getOrDefault(id, new ArrayDeque<>());
        prune(s, strikeWindow, now);
        return new FairUseView(missionsPerMinute, maxConcurrent, ipDailyCeiling, day.size(), minute.size(),
            coolingSeconds(id, now), Math.max(0, strikeLimit - s.size()));
    }

    // ------------------------------------------------------------------

    private long coolingSeconds(UUID id, long now) {
        Long until = coolingUntil.get(id);
        if (until == null) {
            return 0;
        }
        if (until <= now) {
            coolingUntil.remove(id);
            return 0;
        }
        return (until - now + 999) / 1000;
    }

    private static long waitSeconds(Deque<Long> hits, int limit, Duration window, long now) {
        prune(hits, window, now);
        if (hits.size() < limit) {
            return 0;
        }
        long oldest = hits.peekFirst();
        return Math.max(1, (oldest + window.toMillis() - now + 999) / 1000);
    }

    private static void prune(Deque<Long> hits, Duration window, long now) {
        long cutoff = now - window.toMillis();
        while (!hits.isEmpty() && hits.peekFirst() <= cutoff) {
            hits.pollFirst();
        }
    }

    /** Drops idle keys now and then so the maps cannot grow without bound. */
    private void sweep(long now) {
        if (now - lastSweep < 60_000) {
            return;
        }
        lastSweep = now;
        sessionHits.values().removeIf(d -> { prune(d, MINUTE, now); return d.isEmpty(); });
        ipMinute.values().removeIf(d -> { prune(d, MINUTE, now); return d.isEmpty(); });
        ipDay.values().removeIf(d -> { prune(d, DAY, now); return d.isEmpty(); });
        strikes.values().removeIf(d -> { prune(d, strikeWindow, now); return d.isEmpty(); });
        coolingUntil.values().removeIf(t -> t <= now);
        running.values().removeIf(m -> { m.values().removeIf(t -> now - t > MAX_HOLD.toMillis()); return m.isEmpty(); });
    }

    private static String ipOf(DemoSession session) {
        String ip = session.getCreatedIp();
        return ip == null || ip.isBlank() ? "unknown" : ip;
    }

    private static String human(long seconds) {
        if (seconds < 90) {
            return seconds + " seconds";
        }
        long minutes = (seconds + 59) / 60;
        return minutes < 90 ? minutes + " minutes" : ((minutes + 59) / 60) + " hours";
    }
}
