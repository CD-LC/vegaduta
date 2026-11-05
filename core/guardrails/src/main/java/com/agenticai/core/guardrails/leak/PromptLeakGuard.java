package com.agenticai.core.guardrails.leak;

import com.agenticai.core.security.TenantScope;
import java.util.UUID;
import reactor.core.publisher.Flux;

/**
 * The seam the turn uses: arm the system prompt with a canary before the model call, then pass the
 * model's text stream through {@link Turn#guard}. See {@link LeakDetector} for what is and is not
 * caught.
 *
 * <p>{@code mode=off} costs one field read per turn: {@link #begin} returns the shared {@link
 * Turn#NOOP} (no canary, no random bytes, no hashing, no allocation) and {@code guard} returns its
 * argument unchanged.
 *
 * <p>The active guard is process-wide ({@link #shared()}), installed by {@link
 * PromptLeakConfiguration}; before that, and in plain unit tests, it is OFF, so nothing changes
 * unless Spring wires it.
 */
public final class PromptLeakGuard {

    private static volatile PromptLeakGuard shared = new PromptLeakGuard(null, null);

    private final PromptLeakProperties props;
    private final PromptLeakReporter reporter;
    private final PromptLeakMode mode;

    public PromptLeakGuard(PromptLeakProperties props, PromptLeakReporter reporter) {
        this.props = props;
        this.reporter = reporter;
        this.mode = props == null ? PromptLeakMode.OFF : props.mode();
    }

    public static PromptLeakGuard shared() {
        return shared;
    }

    static void install(PromptLeakGuard guard) {
        shared = guard != null ? guard : new PromptLeakGuard(null, null);
    }

    public PromptLeakMode mode() {
        return mode;
    }

    /**
     * Starts a turn. {@code scope} (may be null) is re-applied around the report, which can run on
     * a stream thread without the tenant context (audit rows are tenant data).
     */
    public Turn begin(String systemPrompt, UUID agentId, String sessionId, TenantScope scope) {
        if (mode == PromptLeakMode.OFF) {
            return Turn.NOOP;
        }
        PromptCanary canary = PromptCanary.generate(props.canaryBytes());
        PromptLeakAction action =
                mode == PromptLeakMode.ENFORCE ? props.action() : PromptLeakAction.WARN;
        LeakDetector detector = new LeakDetector(systemPrompt, canary, props, action);
        return new Turn(canary.appendTo(systemPrompt), detector, this, agentId, sessionId, scope);
    }

    private void report(
            UUID agentId, String sessionId, TenantScope scope, LeakDetector.Verdict verdict) {
        if (reporter == null) {
            return;
        }
        Runnable work = () -> reporter.report(agentId, sessionId, mode, verdict);
        if (scope != null) {
            scope.run(work);
        } else {
            work.run();
        }
    }

    /** One turn's armed prompt and output filter. */
    public static final class Turn {
        /** The off state: shared, stateless. */
        static final Turn NOOP = new Turn(null, null, null, null, null, null);

        private final String armedPrompt;
        private final LeakDetector detector;
        private final PromptLeakGuard owner;
        private final UUID agentId;
        private final String sessionId;
        private final TenantScope scope;

        private Turn(
                String armedPrompt,
                LeakDetector detector,
                PromptLeakGuard owner,
                UUID agentId,
                String sessionId,
                TenantScope scope) {
            this.armedPrompt = armedPrompt;
            this.detector = detector;
            this.owner = owner;
            this.agentId = agentId;
            this.sessionId = sessionId;
            this.scope = scope;
        }

        public boolean active() {
            return detector != null;
        }

        /** The prompt to send: {@code original} unchanged when off, else with the canary block. */
        public String systemPrompt(String original) {
            return armedPrompt != null ? armedPrompt : original;
        }

        /**
         * The reply stream with the leak check in line. Each subscription gets its own scan state
         * (a retried or failed-over subscription starts clean). An error flushes the held-back text
         * first, then propagates.
         */
        public Flux<String> guard(Flux<String> stream) {
            if (detector == null) {
                return stream;
            }
            return Flux.defer(
                    () -> {
                        LeakDetector.StreamScan scan =
                                detector.newScan(v -> owner.report(agentId, sessionId, scope, v));
                        return stream.<String>handle(
                                        (chunk, sink) -> {
                                            String out = scan.feed(chunk);
                                            if (!out.isEmpty()) {
                                                sink.next(out);
                                            }
                                            if (scan.blocked()) {
                                                sink.complete();
                                            }
                                        })
                                .concatWith(Flux.defer(() -> flush(scan)))
                                .onErrorResume(
                                        e -> {
                                            Flux<String> rest = flush(scan);
                                            return rest.concatWith(Flux.error(e));
                                        });
                    });
        }

        private static Flux<String> flush(LeakDetector.StreamScan scan) {
            String rest = scan.finish();
            return rest.isEmpty() ? Flux.empty() : Flux.just(rest);
        }
    }
}
