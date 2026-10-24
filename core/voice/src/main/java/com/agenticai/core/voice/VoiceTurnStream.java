package com.agenticai.core.voice;

import com.agenticai.core.agents.turn.AgentChatService;
import com.agenticai.core.agents.turn.ChatTurnRequest;
import com.agenticai.core.agents.turn.TurnContext;
import com.agenticai.core.security.TenantContext;
import com.agenticai.core.security.TenantScope;
import com.agenticai.core.security.logging.LogSafe;
import com.agenticai.core.speech.SpeechStreamNormalizer;
import com.agenticai.core.speech.SpeechTextNormalizer;
import com.agenticai.core.speech.call.CallSpeechQuotaExceededException;
import com.agenticai.core.voice.VoiceCallController.TranscriptionOutcome;
import com.agenticai.core.voice.dto.VoiceTurnAudioChunkEvent;
import com.agenticai.core.voice.dto.VoiceTurnResponse;
import com.agenticai.core.voice.dto.VoiceTurnTranscriptEvent;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.codec.ServerSentEvent;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

/**
 * The streaming half of {@link VoiceCallController#turn}: once speech-to-text has produced the
 * user's text, builds the Server-Sent Event stream ({@code transcript}, interleaved {@code
 * reply-delta}/{@code audio-chunk}, then {@code done} or {@code error}) from the agent's streamed
 * reply and per-sentence TTS, under one absolute wall-clock ceiling. Split out of the controller,
 * which keeps the agent lookup and transcription; logs under the controller's logger category.
 */
final class VoiceTurnStream {

    private static final Logger log = LoggerFactory.getLogger(VoiceCallController.class);

    /**
     * Absolute wall-clock ceiling for {@link VoiceCallController#turn}'s LLM-generation +
     * TTS-synthesis phase (the {@code replyAndAudio} sub-flux built below) - the reliability
     * blueprint's Part 5(d) fix ({@code docs/RELIABILITY-BLUEPRINT-2026-08-06-PART1.md}),
     * specifically the half of it flagged as NOT yet shipped in {@code
     * docs/AGENT-COORDINATION-2026-08-06.md}'s "Session B — closing entry" ("{@code
     * VoiceCallController#turn}'s explicit absolute ceiling did not [ship], since those are core/
     * changes outside this pass's web+voice-agent scope").
     *
     * <p>The STT leg above already completes synchronously before this Flux is even built, so it
     * isn't covered by this timer - it's bounded separately by each provider's own client-side
     * timeout ({@code SidecarClient#transcribeAudioLive}'s javadoc, {@code
     * SarvamSpeechToTextClient}, the tenant's own selected provider). Every individual LLM/TTS HTTP
     * call in THIS phase already carries its own bound too - {@code
     * app.llm.http.stream-idle-timeout} (90s default) for the LLM leg via {@code
     * AgentChatService#chat}'s own {@code Flux#timeout}, and Spring Boot's global {@code
     * spring.http.client.read-timeout} (120s default - see {@code application.yml}'s comment on
     * that property: "Anything that genuinely needs a tighter budget sets its own request
     * factory... and is unaffected by these defaults" - {@code SarvamTextToSpeechClient} and {@link
     * com.agenticai.core.elevenlabs.ElevenLabsClient} both build their {@code RestClient} from the
     * injected {@code RestClient.Builder} with no factory override, so both inherit exactly this
     * global default; verified by reading both classes' constructors, not assumed) for each
     * per-sentence TTS call. NONE of those bound the TOTAL elapsed time across a reply with many
     * sentences, each synthesized separately with concurrency capped at 2 (see {@code
     * synthesizeChunk}'s {@code flatMapSequential} call) - that's the specific gap this constant
     * closes.
     *
     * <p>Deliberately generous - same "safety net, not a latency budget" posture {@code
     * requestLifecycle.ts}'s {@code DEFAULT_TIMEOUT_POLICY} documents for its own {@code
     * absoluteTimeoutMs} (120s) - converts "hangs forever" into "fails within a bounded window with
     * a clear message," nothing tighter. Set higher than the frontend's 120s default specifically
     * because this phase legitimately runs BOTH an LLM generation (up to 90s idle-reset, so a
     * slow-but-steady long reasoning response can itself approach that) AND several sequential/
     * concurrency-2 TTS calls (up to 120s each) for the same turn - 120s would risk false-positive
     * cutoffs on a genuinely healthy long reply.
     *
     * <p><strong>NOT a complete fix for the audit's "pick one coherent budget end-to-end"
     * ask</strong> - two other legs of that same finding are OUT OF THIS FILE's scope and remain
     * open, flagged here rather than silently left unmentioned: {@code web/app/lib/api.ts}'s {@code
     * sendVoiceTurnStream} still aborts only the INITIAL fetch at 45s and, per the audit, that
     * abort is cleared at first byte rather than covering the whole stream - so before this change,
     * NOTHING bounded the window after headers arrived from either side; this backend ceiling is
     * what now actually bounds that window, but the frontend itself still has no matching timeout
     * of its own for the post-first-byte portion of this same call. {@code
     * core/knowledge/SidecarClient.java}'s 30-minute STT read timeout belongs to a different module
     * (out of {@code core/voice}'s scope) and is a background-job-runner-oriented client (this live
     * path uses the separate {@code transcribeAudioLive} method already, per that class's own
     * javadoc - not the 30-minute one) - not touched here.
     */
    private static final Duration TURN_ABSOLUTE_TIMEOUT = Duration.ofMinutes(3);

    private final AgentChatService agentChatService;
    private final VoiceTurnSpeech turnSpeech;

    VoiceTurnStream(AgentChatService agentChatService, VoiceTurnSpeech turnSpeech) {
        this.agentChatService = agentChatService;
        this.turnSpeech = turnSpeech;
    }

    /** The SSE stream for one already-transcribed turn; see {@link VoiceCallController#turn}. */
    Flux<ServerSentEvent<String>> stream(
            UUID agentId,
            String resolvedSessionId,
            String subjectKey,
            TranscriptionOutcome transcriptionOutcome) {
        // userText/transcriptionMethod were reassigned across transcribeAudio's
        // STT fallback chain, so they aren't effectively final - copy them
        // once here for the lambdas below (Mono.defer, doOnNext, etc.) to
        // close over.
        final String resolvedUserText = transcriptionOutcome.text();
        final String resolvedTranscriptionMethod = transcriptionOutcome.method();

        ServerSentEvent<String> transcriptEvent =
                VoiceTurnEvents.sse(
                        "transcript",
                        VoiceTurnEvents.toJson(
                                new VoiceTurnTranscriptEvent(
                                        resolvedUserText, resolvedTranscriptionMethod)));

        // 2) The agent's text reply, streamed - same AgentChatService entry
        // point the web Playground/omnichannel channels use. fullReply
        // accumulates the complete text for the final `done` event; it is
        // only ever touched from this Flux's doOnNext, which Reactive
        // Streams guarantees is serialized for a single subscriber, so a
        // plain (non-thread-safe) StringBuilder is safe here - same
        // reasoning SpeechStreamNormalizer's own javadoc relies on.
        StringBuilder fullReply = new StringBuilder();
        SpeechStreamNormalizer speech = new SpeechStreamNormalizer();
        Sinks.Many<String> sentenceSink = Sinks.many().unicast().onBackpressureBuffer();
        // TTS runs on boundedElastic and the done event on whichever thread completes the stream;
        // both use tenant tables (credentials, quota and audio ledgers), so re-apply the caller's.
        TenantScope tenant = TenantScope.capture();

        ChatTurnRequest chatTurn =
                ChatTurnRequest.builder(agentId, resolvedSessionId, subjectKey, resolvedUserText)
                        .forceFastDepth(true)
                        .build();
        TurnContext turnContext =
                TurnContext.forEntry(TenantContext.current().orElse(null), chatTurn);
        // forceFastDepth=true (reliability blueprint, 2026-08-06 follow-up,
        // contract shared with the LiveKit voice-agent bridge and
        // AgentController#chat's ChatRequest.forceFastDepth): push-to-talk is
        // a latency-sensitive turn, so this ONE turn is dispatched as FAST
        // regardless of the agent's own configured reasoningDepth - the
        // agent's stored/configured depth is never modified, only this
        // in-flight turn's dispatch decision. See AgentChatService#chat's
        // seven-arg overload javadoc for the full contract.
        Flux<ServerSentEvent<String>> replyDeltaEvents =
                agentChatService
                        .chat(turnContext, chatTurn)
                        .doOnNext(
                                token -> {
                                    fullReply.append(token);
                                    speech.accept(token, sentenceSink::tryEmitNext);
                                })
                        .doOnComplete(
                                () -> {
                                    speech.flush(sentenceSink::tryEmitNext);
                                    sentenceSink.tryEmitComplete();
                                })
                        // Let whatever queued sentences already have their TTS in flight
                        // finish (best-effort, via mergeDelayError below) rather than
                        // abandoning them - the caller still hears what was generated
                        // before the failure.
                        .doOnError(e -> sentenceSink.tryEmitComplete())
                        .map(token -> VoiceTurnEvents.sse("reply-delta", token));

        // 3) Text-to-speech, per sentence - same ElevenLabs -> Sarvam ->
        // text-only fallback chain as before (see VoiceTurnSpeech#synthesizeChunk), now run
        // once per SpeechStreamNormalizer-released piece instead of once for
        // the whole reply. flatMapSequential lets consecutive sentences'
        // TTS calls run concurrently (concurrency=2: sentence N+1 can start
        // synthesizing while N is still in flight) while guaranteeing
        // audio-chunk events reach the wire in the same order the sentences
        // were generated in, regardless of which TTS call happens to finish
        // first - that ordering guarantee is the whole reason this uses
        // flatMapSequential rather than plain flatMap.
        List<VoiceTurnSpeech.ChunkResult> chunkResults =
                Collections.synchronizedList(new ArrayList<>());
        Flux<ServerSentEvent<String>> audioChunkEvents =
                sentenceSink
                        .asFlux()
                        .index()
                        .flatMapSequential(
                                seqAndText -> {
                                    long seq = seqAndText.getT1();
                                    String sentence = seqAndText.getT2();
                                    return turnSpeech
                                            .synthesizeChunk(agentId, sentence, tenant)
                                            .doOnNext(chunkResults::add)
                                            .map(
                                                    result ->
                                                            VoiceTurnEvents.sse(
                                                                    "audio-chunk",
                                                                    VoiceTurnEvents.toJson(
                                                                            new VoiceTurnAudioChunkEvent(
                                                                                    seq,
                                                                                    sentence,
                                                                                    Base64
                                                                                            .getEncoder()
                                                                                            .encodeToString(
                                                                                                    result
                                                                                                                            .audioBytes()
                                                                                                                    != null
                                                                                                            ? result
                                                                                                                    .audioBytes()
                                                                                                            : new byte
                                                                                                                    [0]),
                                                                                    result
                                                                                            .method()))));
                                },
                                2);

        Mono<ServerSentEvent<String>> doneEvent =
                Mono.defer(
                        () -> {
                            String replyText =
                                    fullReply.length() > 0 ? fullReply.toString() : "(no reply)";
                            String replySpeechText =
                                    SpeechTextNormalizer.normalizeForSpeech(replyText);
                            tenant.run(() -> turnSpeech.persistTurnAudio(agentId, chunkResults));
                            log.info(
                                    "Voice turn stream completed: agent={} session={}"
                                            + " transcriptionMethod={} lengths(transcript/reply)={}/{}"
                                            + " audioChunks={}",
                                    LogSafe.clean(agentId),
                                    LogSafe.clean(resolvedSessionId),
                                    LogSafe.clean(resolvedTranscriptionMethod),
                                    resolvedUserText.length(),
                                    replyText.length(),
                                    chunkResults.size());
                            return Mono.just(
                                    VoiceTurnEvents.sse(
                                            "done",
                                            VoiceTurnEvents.toJson(
                                                    new VoiceTurnResponse(
                                                            resolvedSessionId,
                                                            resolvedUserText,
                                                            replyText,
                                                            replySpeechText,
                                                            ""))));
                        });

        Flux<ServerSentEvent<String>> replyAndAudio =
                Flux.mergeDelayError(256, replyDeltaEvents, audioChunkEvents)
                        .concatWith(doneEvent)
                        .onErrorResume(
                                e -> {
                                    if (e
                                            instanceof
                                            CallSpeechQuotaExceededException quotaExceeded) {
                                        // Distinct wording, same `error` SSE event (2026-08-08
                                        // audit fix #1) - see VoiceTurnSpeech#synthesizeChunk's
                                        // javadoc for why
                                        // this exception reaches here uncaught instead of being
                                        // silently downgraded to a Sarvam/text-only fallback.
                                        // INFO, not WARN: this is an expected plan-tier gate,
                                        // not a stream failure.
                                        log.info(
                                                "Voice turn stream stopped for agent {} session {}:"
                                                        + " voice quota exceeded: {}",
                                                LogSafe.clean(agentId),
                                                LogSafe.clean(resolvedSessionId),
                                                LogSafe.clean(quotaExceeded.getMessage()));
                                        return Flux.just(
                                                VoiceTurnEvents.sse(
                                                        "error",
                                                        VoiceTurnEvents.quotaExceededMessage(
                                                                quotaExceeded)));
                                    }
                                    log.warn(
                                            "Voice turn stream failed for agent {} session {} after"
                                                    + " streaming had already started: {}",
                                            LogSafe.clean(agentId),
                                            LogSafe.clean(resolvedSessionId),
                                            LogSafe.clean(e.getMessage()),
                                            e);
                                    return Flux.just(
                                            VoiceTurnEvents.sse(
                                                    "error",
                                                    VoiceTurnEvents.midStreamFailureMessage(e)));
                                });

        // Part 5(d) absolute ceiling - see TURN_ABSOLUTE_TIMEOUT's javadoc for
        // why this is NOT Flux#timeout (that operator only resets on a
        // per-item basis and would never fire while output keeps trickling
        // in - exactly the gap a genuinely slow-but-progressing reply would
        // slip through). takeUntilOther races replyAndAudio against a plain
        // delay signal: per that operator's own contract, whichever
        // completes first cancels the other, so a normal completion well
        // inside the window costs nothing extra, and a forced cutover
        // properly cancels the still-in-flight LLM/TTS work upstream
        // (cancellation propagates through mergeDelayError to
        // agentChatService.chat()'s Flux and the TTS sentenceSink, not just
        // this HTTP response - the backend actually stops working, it
        // doesn't just stop telling the client about it). Any audio-chunk/
        // reply-delta events already emitted before the ceiling fires have
        // already reached the client and keep playing; only VoiceTurnSpeech#persistTurnAudio
        // (inside doneEvent, reached only on natural completion) is skipped
        // for a forced cutover - an audit-ledger gap, not a user-facing one.
        AtomicBoolean ceilingHit = new AtomicBoolean(false);
        Mono<Long> ceilingSignal =
                Mono.delay(TURN_ABSOLUTE_TIMEOUT).doOnNext(tick -> ceilingHit.set(true));
        Flux<ServerSentEvent<String>> boundedReplyAndAudio =
                replyAndAudio
                        .takeUntilOther(ceilingSignal)
                        .concatWith(
                                Mono.defer(
                                        () -> {
                                            if (!ceilingHit.get()) {
                                                return Mono.empty();
                                            }
                                            log.warn(
                                                    "Voice turn stream for agent {} session {}"
                                                            + " exceeded the {}s absolute ceiling -"
                                                            + " forcing cutover",
                                                    LogSafe.clean(agentId),
                                                    LogSafe.clean(resolvedSessionId),
                                                    LogSafe.clean(
                                                            TURN_ABSOLUTE_TIMEOUT.toSeconds()));
                                            return Mono.just(
                                                    VoiceTurnEvents.sse(
                                                            "error",
                                                            VoiceTurnEvents
                                                                    .absoluteTimeoutMessage()));
                                        }));

        return Flux.concat(Flux.just(transcriptEvent), boundedReplyAndAudio);
    }
}
