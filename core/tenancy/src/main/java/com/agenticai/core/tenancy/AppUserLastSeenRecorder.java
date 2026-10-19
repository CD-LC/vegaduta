package com.agenticai.core.tenancy;

import com.agenticai.core.security.UserActivityRecorder;
import com.agenticai.core.security.concurrent.TenantAwareExecutors;
import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * V475 - stamps {@code control_plane.app_user.last_seen_at} from authenticated requests ({@link
 * com.agenticai.core.security.TenantContextFilter} calls {@link #recordSeen}), the platform's only
 * "last activity" signal.
 *
 * <p><b>Cost on the request path: one map lookup.</b> Two throttles keep this from ever becoming a
 * per-request write:
 *
 * <ol>
 *   <li>an in-memory per-user guard - a user already stamped by THIS replica in the last {@link
 *       #THROTTLE} is skipped before any work is queued;
 *   <li>the UPDATE's own {@code last_seen_at < now() - interval '5 minutes'} predicate, so N
 *       replicas still produce at most one row write per user per window (the others match zero
 *       rows).
 * </ol>
 *
 * The write itself runs on a single background thread with a small bounded queue that DROPS on
 * overflow - a database stall can delay or lose a last-seen stamp, never a request. Plain {@link
 * JdbcTemplate}, not JPA: the background thread has no {@code TenantContext}, and the statement is
 * already {@code control_plane}-qualified.
 *
 * <p>The guard map is bounded crudely (cleared past {@link #MAX_TRACKED} users) - clearing only
 * costs one extra no-op UPDATE per active user, so a real LRU cache would buy nothing here.
 */
@Component
public class AppUserLastSeenRecorder implements UserActivityRecorder {

    private static final Logger log = LoggerFactory.getLogger(AppUserLastSeenRecorder.class);

    static final Duration THROTTLE = Duration.ofMinutes(5);
    static final int MAX_TRACKED = 50_000;
    private static final int QUEUE_CAPACITY = 1_000;

    static final String UPDATE_SQL =
            "UPDATE control_plane.app_user SET last_seen_at = now() WHERE keycloak_user_id = ? AND"
                    + " (last_seen_at IS NULL OR last_seen_at < now() - interval '5 minutes')";

    private final JdbcTemplate jdbcTemplate;
    private final Executor executor;
    private final Clock clock;
    private final Map<String, Long> lastStampedMillis = new ConcurrentHashMap<>();

    @Autowired
    public AppUserLastSeenRecorder(JdbcTemplate jdbcTemplate) {
        this(jdbcTemplate, boundedSingleThreadExecutor(), Clock.systemUTC());
    }

    AppUserLastSeenRecorder(JdbcTemplate jdbcTemplate, Executor executor, Clock clock) {
        this.jdbcTemplate = jdbcTemplate;
        this.executor = executor;
        this.clock = clock;
    }

    @Override
    public void recordSeen(String tenantId, String subject) {
        if (subject == null || subject.isBlank()) {
            return;
        }
        long now = clock.millis();
        Long previous = lastStampedMillis.get(subject);
        if (previous != null && now - previous < THROTTLE.toMillis()) {
            return;
        }
        if (lastStampedMillis.size() >= MAX_TRACKED) {
            lastStampedMillis.clear();
        }
        lastStampedMillis.put(subject, now);
        try {
            executor.execute(() -> stamp(subject));
        } catch (RejectedExecutionException e) {
            // Queue full: the database is slow. Forget the guard entry so the
            // next request retries, and move on - never the request's problem.
            lastStampedMillis.remove(subject);
        }
    }

    private void stamp(String subject) {
        try {
            jdbcTemplate.update(UPDATE_SQL, subject);
        } catch (RuntimeException e) {
            lastStampedMillis.remove(subject);
            log.debug(
                    "Could not stamp last_seen_at for keycloak user '{}' - {}",
                    subject,
                    e.getMessage());
        }
    }

    private static Executor boundedSingleThreadExecutor() {
        ThreadPoolExecutor executor =
                TenantAwareExecutors.newThreadPool(
                        1,
                        1,
                        0L,
                        TimeUnit.MILLISECONDS,
                        new ArrayBlockingQueue<>(QUEUE_CAPACITY),
                        TenantAwareExecutors.threadFactory("app-user-last-seen", true),
                        new ThreadPoolExecutor.AbortPolicy());
        return executor;
    }
}
