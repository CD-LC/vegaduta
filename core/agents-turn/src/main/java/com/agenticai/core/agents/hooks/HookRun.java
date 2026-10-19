package com.agenticai.core.agents.hooks;

import com.agenticai.core.security.concurrent.TenantAwareSchedulers;
import java.util.List;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * The model-level and run-level hooks of ONE chat turn: {@code BEFORE_MODEL}, {@code AFTER_MODEL}, {@code ON_ERROR}
 * and {@code ON_RUN_END}. {@code AgentChatService#chat} creates one through {@code AgentHookRuntime#beginRun} (a
 * {@code null} return - flag off, or no model/run hook configured - means the chat path is untouched) and makes
 * three calls on it: {@link #beforeModel}, {@link #contextNote}, {@link #wrap}.
 *
 * <h2>AFTER_MODEL buffers - on purpose, and only when it must</h2>
 * Redacting, blocking or webhook-vetting a reply needs the whole reply, so an agent with such an {@code AFTER_MODEL}
 * hook gives up live token streaming: chunks are held back and one transformed chunk is delivered when the model
 * finishes. Empty keep-alive chunks still pass through immediately so the connection stays demonstrably alive. An
 * agent whose {@code AFTER_MODEL} hooks only {@code log} keeps streaming. Limit of this design, stated plainly: the
 * transformation applies to what is delivered to the caller; the assistant turn the chat layer persists to session
 * history is written before this stage sees it, so a redacted reply is still the stored one.
 *
 * <h2>Errors and run end</h2>
 * Both are informational and fire off the response thread; a slow webhook cannot hold the stream open.
 */
public final class HookRun {

    private final HookEngine engine;
    private final HookCtx ctx;
    private final List<EffectiveHook> beforeModel;
    private final List<EffectiveHook> afterModel;
    private final List<EffectiveHook> onError;
    private final List<EffectiveHook> onRunEnd;
    private volatile String note;

    HookRun(
        HookEngine engine, HookCtx ctx, List<EffectiveHook> beforeModel, List<EffectiveHook> afterModel,
        List<EffectiveHook> onError, List<EffectiveHook> onRunEnd
    ) {
        this.engine = engine;
        this.ctx = ctx;
        this.beforeModel = List.copyOf(beforeModel);
        this.afterModel = List.copyOf(afterModel);
        this.onError = List.copyOf(onError);
        this.onRunEnd = List.copyOf(onRunEnd);
    }

    /**
     * Runs the {@code BEFORE_MODEL} hooks.
     *
     * @return a refusal to deliver instead of calling the model, or {@code null} to go ahead
     */
    public String beforeModel(String userMessage) {
        if (beforeModel.isEmpty()) {
            return null;
        }
        HookEngine.ModelPre pre = engine.beforeModel(ctx, beforeModel, userMessage);
        this.note = pre.note();
        return pre.refusal();
    }

    /** The {@code append_context} text for this turn's per-turn context block, or {@code null}. */
    public String contextNote() {
        return note;
    }

    /**
     * True when a cached reply could not stand in for a fresh one: a {@code BEFORE_MODEL append_context} note changes
     * what the model would say, and any {@code AFTER_MODEL} hook other than {@code log} changes what is delivered.
     * Everything else (log, limit, block and webhook before the model, error and run-end observers) either runs
     * before the cache lookup or does not touch the reply, so a log-only set keeps the response caches.
     */
    public boolean needsFreshModelReply() {
        return beforeModel.stream().anyMatch(h -> h.def().action() == HookActionType.APPEND_CONTEXT)
            || afterModel.stream().anyMatch(h -> h.def().action() != HookActionType.LOG);
    }

    /** A refusal as a reply stream, still reported to {@code ON_ERROR} / {@code ON_RUN_END} hooks. */
    public Flux<String> refusal(String text) {
        return observe(Flux.just(text));
    }

    /** Applies {@code AFTER_MODEL} (buffering only when a hook has to see the whole reply), then error and run-end observers. */
    public Flux<String> wrap(Flux<String> result) {
        Flux<String> out = result;
        if (!afterModel.isEmpty()) {
            boolean transforms = afterModel.stream().anyMatch(h -> h.def().action() != HookActionType.LOG);
            // Log-only: the audit write is JDBC, so it goes to the async executor, never the completion thread.
            out = transforms ? transform(result) : result.doOnComplete(() -> engine.afterModelLogAsync(ctx, afterModel));
        }
        return observe(out);
    }

    private Flux<String> transform(Flux<String> result) {
        return Flux.defer(() -> {
            StringBuilder reply = new StringBuilder();
            Flux<String> passthrough = result.filter(chunk -> {
                if (chunk == null) {
                    return false;
                }
                if (chunk.isEmpty()) {
                    return true;
                }
                reply.append(chunk);
                return false;
            });
            Flux<String> tail = Flux.defer(() -> {
                String full = reply.toString();
                if (full.isEmpty()) {
                    return Flux.<String>empty();
                }
                return Mono.fromCallable(() -> engine.afterModel(ctx, afterModel, full))
                    .subscribeOn(TenantAwareSchedulers.boundedElastic())
                    .flux();
            });
            return passthrough.concatWith(tail);
        });
    }

    private Flux<String> observe(Flux<String> stream) {
        Flux<String> out = stream;
        if (!onError.isEmpty()) {
            out = out.doOnError(e -> engine.onRunError(ctx, onError, e));
        }
        if (!onRunEnd.isEmpty()) {
            out = out.doFinally(signal -> engine.onRunEnd(ctx, onRunEnd, signal.name()));
        }
        return out;
    }
}
