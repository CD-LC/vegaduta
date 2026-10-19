package com.agenticai.core.resilience;

import com.agenticai.core.security.net.SafeHttpClients;
import com.agenticai.core.security.net.SsrfGuard;
import java.time.Duration;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.Environment;
import org.springframework.http.client.ClientHttpRequestFactory;

/**
 * Connect + read timeout for ONE named outbound dependency, configured as
 * {@code app.http.<name>.connect-timeout} / {@code app.http.<name>.read-timeout}.
 *
 * <p>Used for the few clients that build their own transport and previously had NO timeout. Every
 * other outbound client either already carries explicit timeouts or is built from the
 * auto-configured {@code RestClient.Builder}, which inherits {@code spring.http.client.*}
 * (10s / 120s) - see docs/plans/CORE-RESILIENCE-AUDIT.md for the full table.
 *
 * <p>Defaults are chosen per call site to be generous for a call that works (so this is a
 * bound on "hangs forever", not a latency budget) and are passed by the caller. A missing,
 * zero or negative configured value falls back to the caller's default, so a bad environment
 * variable can never disable the bound again.
 *
 * <p>This is for request/response calls. LLM streaming is deliberately NOT governed by this
 * class: a streaming read timeout must be a per-chunk idle timeout, which
 * {@code app.llm.http.stream-idle-timeout} already provides.
 */
public record OutboundHttpTimeouts(Duration connectTimeout, Duration readTimeout) {

    public OutboundHttpTimeouts {
        if (connectTimeout == null || connectTimeout.isZero() || connectTimeout.isNegative()) {
            throw new IllegalArgumentException("connectTimeout must be positive");
        }
        if (readTimeout == null || readTimeout.isZero() || readTimeout.isNegative()) {
            throw new IllegalArgumentException("readTimeout must be positive");
        }
    }

    /**
     * Reads {@code app.http.<name>.connect-timeout} and {@code .read-timeout} from {@code env};
     * each absent / non-positive value is replaced by the matching default.
     */
    public static OutboundHttpTimeouts resolve(
        Environment env, String name, Duration defaultConnect, Duration defaultRead
    ) {
        Binder binder = Binder.get(env);
        String prefix = "app.http." + name;
        Duration connect = positiveOr(
            binder.bind(prefix + ".connect-timeout", Bindable.of(Duration.class)).orElse(null), defaultConnect);
        Duration read = positiveOr(
            binder.bind(prefix + ".read-timeout", Bindable.of(Duration.class)).orElse(null), defaultRead);
        return new OutboundHttpTimeouts(connect, read);
    }

    /**
     * A request factory whose client resolves through {@link SsrfGuard} (validated
     * answer = connected answer, no DNS-rebinding window) carrying these timeouts.
     * The guard is read at call time so the operator allow-list installed during
     * startup is always in effect; callers reach only public or allow-listed hosts.
     */
    public ClientHttpRequestFactory requestFactory() {
        return SafeHttpClients.requestFactory(
            SsrfGuard.defaultGuard(), connectTimeout, readTimeout, false);
    }

    private static Duration positiveOr(Duration value, Duration fallback) {
        return value == null || value.isZero() || value.isNegative() ? fallback : value;
    }
}
