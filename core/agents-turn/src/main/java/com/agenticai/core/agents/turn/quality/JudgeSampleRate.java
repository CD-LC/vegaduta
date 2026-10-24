package com.agenticai.core.agents.turn.quality;

import com.agenticai.core.tenancy.PlatformSettingsService;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongSupplier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * The fraction of live turns the background quality judge samples, settable at run time. The configured value
 * ({@code app.llm.judge.sample-rate}, 5% as shipped, env {@code APP_LLM_JUDGE_SAMPLE_RATE}) is the default; an
 * admin sets the platform setting {@value #KEY} (0..1) to change it without a restart, and an unset or
 * unparseable row falls back to the configured value. The result is clamped to [0, 1], so a mistyped value can
 * neither sample everything by accident nor make the rate negative.
 *
 * <p>The rate is read on every turn, so the platform-setting read is reused for {@code
 * app.llm.judge.sample-rate-cache-ms} (10 s as shipped), the same time-box the other per-turn admin knobs use. Without a
 * {@link PlatformSettingsService} (a hand-built service in a test) the configured value is used as is.
 */
@Component
public class JudgeSampleRate {

    /** The platform setting that overrides {@code app.llm.judge.sample-rate} while a row exists. */
    public static final String KEY = "platform.quality.judge.sample-rate";

    private record Cached(double rate, long loadedAtMs) {}

    private final PlatformSettingsService settings;
    private final long cacheMs;
    private final LongSupplier clock;
    private final AtomicReference<Cached> cached = new AtomicReference<>();

    @Autowired
    public JudgeSampleRate(
        PlatformSettingsService settings, @Value("${app.llm.judge.sample-rate-cache-ms:10000}") long cacheMs
    ) {
        this(settings, cacheMs, System::currentTimeMillis);
    }

    JudgeSampleRate(PlatformSettingsService settings, long cacheMs, LongSupplier clock) {
        this.settings = settings;
        this.cacheMs = Math.max(0, cacheMs);
        this.clock = clock;
    }

    /** @param configured the rate {@code app.llm.judge.sample-rate} gives, used while no setting row exists */
    public double current(double configured) {
        if (settings == null) {
            return clamp(configured);
        }
        long now = clock.getAsLong();
        Cached hit = cached.get();
        if (hit != null && now - hit.loadedAtMs() < cacheMs) {
            return hit.rate();
        }
        double fresh = clamp(settings.getDouble(KEY, configured));
        cached.set(new Cached(fresh, now));
        return fresh;
    }

    private static double clamp(double rate) {
        if (Double.isNaN(rate)) {
            return 0.0;
        }
        return Math.max(0.0, Math.min(1.0, rate));
    }
}
