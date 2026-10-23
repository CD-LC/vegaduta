package com.agenticai.core.chat;

import com.agenticai.core.providers.PlatformHostedLlmProviderService;
import com.agenticai.core.providers.PlatformHostedLlmProviderService.HostedProviderSelection;
import com.agenticai.core.security.startup.BackgroundStartupTask;
import java.time.Clock;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Wakes the platform pool's model before a person's first chat. A hosted model that scales to zero
 * (or a local Ollama that unloaded it) answers the first request only after loading the model,
 * which measured 70 s and more in production; the console calls {@code POST /api/platform-llm/warm}
 * as soon as someone signs in, so that load happens while they look at the page.
 *
 * <p>The warm-up is a one-token completion built by {@link HostedLlmChatClientFactory}, the same
 * factory a FREE-tier turn uses, so the model is loaded with the options the real turn sends (a
 * different Ollama {@code num_ctx} would make the first turn reload it). It runs on a background
 * thread, at most one at a time, and no more than once per {@code app.platform-llm.warm.interval}
 * per core instance: a model that was used a minute ago is still loaded, and a room of people
 * signing in at once makes one request, not one each. Failures are logged and never reach the
 * caller. The pool table is control-plane, so no tenant context is needed. {@code
 * app.platform-llm.warm.enabled=false} (env {@code APP_PLATFORM_LLM_WARM_ENABLED}) turns it off.
 */
@Component
public class PlatformPoolWarmer {

    private static final Logger log = LoggerFactory.getLogger(PlatformPoolWarmer.class);

    private final PlatformHostedLlmProviderService pool;
    private final HostedLlmChatClientFactory clients;
    private final long intervalMillis;
    private final boolean enabled;
    private final AtomicBoolean running = new AtomicBoolean();
    private final AtomicLong lastStartedAt = new AtomicLong(Long.MIN_VALUE / 2);
    private Clock clock = Clock.systemUTC();

    @Autowired
    public PlatformPoolWarmer(
            PlatformHostedLlmProviderService pool,
            HostedLlmChatClientFactory clients,
            @Value("${app.platform-llm.warm.enabled:true}") boolean enabled,
            @Value("${app.platform-llm.warm.interval-seconds:240}") long intervalSeconds) {
        this.enabled = enabled;
        this.pool = pool;
        this.clients = clients;
        this.intervalMillis = Math.max(0, intervalSeconds) * 1000L;
    }

    void setClock(Clock clock) {
        this.clock = clock;
    }

    /**
     * Starts a warm-up unless one is running or one started within the interval.
     *
     * @return true when a warm-up was started by this call
     */
    public boolean warmAsync() {
        if (!claim()) {
            return false;
        }
        BackgroundStartupTask.run(true, "platform-llm-warm", this::warmAndRelease);
        return true;
    }

    /** Claims the single warm-up slot; package-private so tests can drive it without a thread. */
    boolean claim() {
        if (!enabled) {
            return false;
        }
        long now = clock.millis();
        if (now - lastStartedAt.get() < intervalMillis || !running.compareAndSet(false, true)) {
            return false;
        }
        lastStartedAt.set(now);
        return true;
    }

    void warmAndRelease() {
        try {
            warmNow();
        } finally {
            running.set(false);
        }
    }

    /** One blocking warm-up of the pool's top row; never throws. */
    Optional<Long> warmNow() {
        Optional<HostedProviderSelection> top;
        try {
            top = pool.resolveTopPriorityEnabled();
        } catch (RuntimeException e) {
            log.debug(
                    "Platform model warm-up skipped: the pool could not be read ({})",
                    e.toString());
            return Optional.empty();
        }
        if (top.isEmpty()) {
            return Optional.empty();
        }
        HostedProviderSelection selection = top.get();
        long started = clock.millis();
        try {
            clients.create(selection.provider(), selection.decryptedApiKey(), 1, 0.0, 1.0)
                    .prompt()
                    // A probe on a member's request thread is nobody's usage: no call-log row in
                    // that member's tenant, no spend counter, no metric.
                    .advisors(a -> a.param(LlmMetricsAdvisor.SKIP_OBSERVATION_PARAM, true))
                    .user("hi")
                    .call()
                    .content();
        } catch (RuntimeException e) {
            // A one-token reply can be refused (a reasoning model that needs more tokens) after the
            // model is already loaded, so a failure here is not worth more than a debug line.
            log.debug(
                    "Platform model warm-up of '{}' ended with {}",
                    selection.provider().getModel(),
                    e.toString());
        }
        long took = clock.millis() - started;
        log.info("Platform model '{}' warmed in {} ms", selection.provider().getModel(), took);
        return Optional.of(took);
    }
}
