package com.agenticai.core.agents.research;

import com.agenticai.core.agents.tools.web.DailyWindowCounter;
import com.agenticai.core.agents.tools.web.DeepResearchDailyAllowance;
import com.agenticai.core.security.logging.LogSafe;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/**
 * Per-user-per-day cap on research RUNS started through {@code /api/research/runs}, and the number
 * the page shows as "runs left today".
 *
 * <p>This counter is NOT what bounds platform-paid search spend: the research agent is an ordinary
 * agent its owner can also chat with or submit jobs to directly, and any agent can have the
 * capability switched on. Spend is bounded where it happens, by {@link DeepResearchDailyAllowance}
 * inside the {@code deep_research} tool call. This service consults that allowance too, so a user
 * whose searches are used up is refused here with a clear 429 instead of being handed a run that
 * cannot search.
 *
 * <p>Redis fixed window of 24 hours from a user's first run, keyed per (tenant, user). If Redis is
 * down the count continues in-process (per replica) rather than failing open - see {@link
 * DailyWindowCounter}.
 */
@Component
public class ResearchRunUsageService {

    private static final org.slf4j.Logger log =
            org.slf4j.LoggerFactory.getLogger(ResearchRunUsageService.class);

    static final String KEY_PREFIX = "agentic:research-runs:daily:";

    private final DailyWindowCounter counter;
    private final ResearchRunProperties properties;
    private final DeepResearchDailyAllowance searchAllowance;

    @Autowired
    public ResearchRunUsageService(
            ObjectProvider<StringRedisTemplate> redis,
            ResearchRunProperties properties,
            ObjectProvider<DeepResearchDailyAllowance> searchAllowance) {
        this(redis, properties, searchAllowance == null ? null : searchAllowance.getIfAvailable());
    }

    /** Without a search allowance (tests, slim contexts): the run counter alone. */
    ResearchRunUsageService(
            ObjectProvider<StringRedisTemplate> redis, ResearchRunProperties properties) {
        this(redis, properties, (DeepResearchDailyAllowance) null);
    }

    ResearchRunUsageService(
            ObjectProvider<StringRedisTemplate> redis,
            ResearchRunProperties properties,
            DeepResearchDailyAllowance searchAllowance) {
        // Local fallback: a Redis outage degrades to a per-replica cap instead of silently lifting
        // the run cap.
        this.counter = new DailyWindowCounter(redis, true);
        this.properties = properties;
        this.searchAllowance = searchAllowance;
    }

    /**
     * Runs this user may still start in the current window, or {@code null} when no cap is
     * configured. 0 when the user's daily search allowance is used up, whatever the run counter
     * says. Read-only: opening the page must not spend anything.
     */
    public Integer remainingToday(String tenantId, String subject) {
        if (searchesUsedUp(tenantId, subject)) {
            return 0;
        }
        int cap = properties.getMaxPerUserPerDay();
        if (cap <= 0) {
            return null;
        }
        return (int) Math.max(0, cap - counter.current(key(tenantId, subject)));
    }

    /**
     * Counts one run against the caller's window, or refuses with 429 when the run cap or the
     * search allowance is already used. Call BEFORE the job is submitted; call {@link #release} if
     * the submission then fails.
     */
    public void reserve(String tenantId, String subject) {
        if (searchesUsedUp(tenantId, subject)) {
            throw new ResponseStatusException(
                    HttpStatus.TOO_MANY_REQUESTS,
                    "You have used today's research search allowance. It resets within 24 hours - try again later.");
        }
        int cap = properties.getMaxPerUserPerDay();
        if (cap <= 0) {
            return;
        }
        String key = key(tenantId, subject);
        long count = counter.add(key, 1);
        if (count > cap) {
            // Give the slot back so a refused attempt does not push the counter ever further past
            // the cap.
            counter.undo(key, 1);
            throw new ResponseStatusException(
                    HttpStatus.TOO_MANY_REQUESTS,
                    "You have used today's "
                            + cap
                            + " research runs. The allowance resets 24 hours after your "
                            + "first run of the day - try again later.");
        }
    }

    /** Returns a reserved slot after a submission that did not produce a job. Never throws. */
    public void release(String tenantId, String subject) {
        if (properties.getMaxPerUserPerDay() <= 0) {
            return;
        }
        counter.giveBack(key(tenantId, subject), 1);
    }

    private boolean searchesUsedUp(String tenantId, String subject) {
        try {
            return searchAllowance != null && searchAllowance.remaining(tenantId, subject) <= 0;
        } catch (RuntimeException e) {
            log.warn(
                    "Could not read the daily search allowance for {} - not refusing the run on its account",
                    LogSafe.clean(subject),
                    e);
            return false;
        }
    }

    private static String key(String tenantId, String subject) {
        return KEY_PREFIX + tenantId + ":" + subject;
    }
}
