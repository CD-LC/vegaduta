package com.agenticai.core.agents.turn;

import com.agenticai.core.security.TenantScope;
import java.util.function.Supplier;
import org.reactivestreams.Publisher;
import reactor.core.publisher.Flux;

/**
 * Subscribes a {@link Flux} with a captured tenant context in force on the subscribing thread, and
 * puts the thread back as it found it. This is the reactive counterpart of {@link TenantScope#run}
 * for code that hops onto a pooled Reactor scheduler ({@code
 * subscribeOn(Schedulers.boundedElastic())}): everything that runs synchronously at subscription
 * time (the chat client's request assembly and the advisors that read the tenant) sees the right
 * tenant, and the pooled thread is never left holding it afterwards.
 *
 * <p>Do not do this with {@code TenantContext.set(...)} inside {@code Flux.defer} and a {@code
 * doFinally(clear)}: {@code doFinally} runs on whichever thread delivers the terminal signal, not
 * on the thread that set the context, so the pooled thread keeps the tenant until it happens to be
 * reused by unrelated work (a wrong-tenant read or write).
 */
public final class TenantScopedFlux {

    private TenantScopedFlux() {}

    /**
     * Subscribes to {@code source} with {@code scope}'s tenant context in force on the subscribing
     * thread, so everything that runs synchronously at subscription time sees the originating
     * tenant rather than none. An empty scope subscribes exactly as {@code Flux.defer} would.
     */
    public static <T> Flux<T> subscribeInScope(TenantScope scope, Supplier<Flux<T>> source) {
        if (scope == null || scope.isEmpty()) {
            return Flux.defer(source);
        }
        Publisher<T> scoped =
                subscriber -> scope.run(() -> Flux.defer(source).subscribe(subscriber));
        return Flux.from(scoped);
    }
}
