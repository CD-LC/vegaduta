package com.agenticai.core.speech;

import java.util.function.Consumer;
import java.util.function.LongSupplier;

/**
 * Applies {@link SpeechTextNormalizer} to a reply that arrives as a STREAM of
 * partial LLM tokens rather than as one finished string.
 *
 * <p><b>Why this exists.</b> Every other text-to-speech path on this platform
 * (ElevenLabs, Sarvam, TwiML {@code <Say>}) has the whole reply in hand and can
 * normalize it in one call. Twilio's Conversation Relay does not: {@code
 * VoiceRelayWebSocketHandler} subscribes to {@code AgentChatService#chat}'s
 * {@code Flux<String>} and forwards each token to Twilio as it arrives, so that
 * Twilio's own TTS can start speaking before the model has finished thinking.
 * Normalizing each token as it goes by does not work, for two independent
 * reasons:
 * <ul>
 *   <li><b>Split markers.</b> Token boundaries fall wherever the model's
 *       tokenizer put them, so {@code **bold**} routinely arrives as
 *       {@code "*"}, {@code "*bold"}, {@code "*"}, {@code "*"}. No single
 *       token contains a matched pair, so the emphasis rules never fire; worse,
 *       a token that is exactly {@code "**"} normalizes to nothing, and
 *       {@link SpeechTextNormalizer#normalizeForSpeech}'s deliberate
 *       never-empty fallback then hands back {@code "**"} - which is precisely
 *       the "voice reads all the special characters" bug.</li>
 *   <li><b>Glued words.</b> Normalization collapses and trims whitespace, so
 *       {@code "hello"} + {@code " world"} normalized separately and
 *       concatenated by the synthesizer becomes "helloworld".</li>
 * </ul>
 *
 * <p><b>What it does instead.</b> Tokens are accumulated and only released at a
 * point where treating the accumulated text as a standalone piece of prose is
 * safe:
 * <ul>
 *   <li>a <b>sentence boundary</b> - {@code . ! ? : ;} or a Devanagari danda
 *       (U+0964/U+0965), Urdu full stop (U+06D4) or ideographic full stop
 *       (U+3002) - but only when the very next character is whitespace, so
 *       {@code 3.14} and {@code 10:30} are not cut in half;</li>
 *   <li>a <b>newline</b>, which ends a heading, a list item or a table row and
 *       is therefore always a safe cut;</li>
 *   <li>the buffer passing {@link #DEFAULT_MAX_BUFFERED_CHARS}, or standing
 *       longer than {@link #DEFAULT_MAX_BUFFER_AGE_MILLIS} (checked when the
 *       next token arrives, so a slowly-generating model still speaks) - both
 *       of which cut at the last <b>word</b> boundary, never mid-word.</li>
 * </ul>
 * Each released piece goes through {@link
 * SpeechTextNormalizer#normalizeFragmentForSpeech}, whose contract is the one
 * a streaming caller needs: a piece that was pure markup comes back empty and
 * is simply not sent, instead of being spoken as itself.
 *
 * <p><b>Never cut inside a code fence.</b> A {@code ```} block spans many
 * lines, every one of them a tempting newline boundary, and cutting there would
 * both read the code aloud and say "code block." once per piece. So while the
 * buffer holds an odd number of fence lines the buffer simply holds - there is
 * nothing worth speaking inside a fence anyway, so the added latency costs the
 * caller nothing. A fence the model never closes is resolved by {@link
 * #flush}, which is unconditional.
 *
 * <p><b>Word joining.</b> Pieces are emitted with a leading space (the
 * synthesizer concatenates them, and the normalizer has trimmed the original
 * one), except for the first piece and for the pathological cut that lands
 * mid-word - a run longer than the hard cap with no whitespace in it at all,
 * which is the only cut this class makes that is not at a word boundary.
 *
 * <p><b>Not thread-safe, and does not need to be.</b> One instance belongs to
 * one turn (one caller utterance). Reactive Streams guarantees that {@code
 * onNext} calls are serialized and that {@code onComplete} happens-after the
 * last of them, which is exactly the ordering {@link #accept} and {@link
 * #flush} rely on - so it is safe to drive from {@code
 * Schedulers.boundedElastic()} the way {@code VoiceRelayWebSocketHandler} does,
 * and unsafe to share between turns or sessions. On an interrupt the
 * subscription is disposed, {@code onComplete} never runs, and the instance is
 * dropped with whatever it was holding - which is the correct behaviour: a
 * caller who has started speaking again must not hear the rest of the previous
 * answer.
 */
public final class SpeechStreamNormalizer {

    /** Soft cap: past this many buffered characters, cut at the last word boundary. */
    public static final int DEFAULT_MAX_BUFFERED_CHARS = 180;

    /**
     * How long text may sit in the buffer before being spoken anyway (checked
     * on token arrival). A model generating slowly enough that one sentence
     * takes longer than this is better served by speaking a clause early than
     * by leaving the caller listening to silence.
     */
    public static final long DEFAULT_MAX_BUFFER_AGE_MILLIS = 600L;

    /**
     * Sentence terminators. Same set as {@code SpeechTextNormalizer}'s, which
     * is Latin plus the danda/double danda (Hindi, Marathi), the Urdu full stop
     * and the ideographic full stop - a Devanagari reply ends its sentences in
     * U+0964, never in a Latin full stop, and would otherwise never hit a
     * boundary at all.
     */
    private static final String SENTENCE_ENDERS = ".!?:;\u0964\u0965\u06D4\u3002";

    private final StringBuilder buffer = new StringBuilder();
    private final int maxBufferedChars;
    /** Beyond this, cut even without a word boundary - a defence against a long run with no whitespace in it. */
    private final int hardMaxBufferedChars;
    private final long maxBufferAgeNanos;
    private final LongSupplier nanoClock;

    private long bufferStartedNanos;
    private boolean anythingSpoken;
    /** Whether the last piece cut ended in the middle of a word - see {@link #emit}. */
    private boolean previousPieceEndedMidWord;

    public SpeechStreamNormalizer() {
        this(DEFAULT_MAX_BUFFERED_CHARS, DEFAULT_MAX_BUFFER_AGE_MILLIS, System::nanoTime);
    }

    /**
     * @param maxBufferedChars soft cap - see {@link #DEFAULT_MAX_BUFFERED_CHARS}
     * @param maxBufferAgeMillis see {@link #DEFAULT_MAX_BUFFER_AGE_MILLIS}
     * @param nanoClock monotonic nanosecond source; {@code System::nanoTime} in
     *     production, a controllable one in tests (the age threshold is
     *     otherwise untestable without sleeping)
     */
    public SpeechStreamNormalizer(int maxBufferedChars, long maxBufferAgeMillis, LongSupplier nanoClock) {
        this.maxBufferedChars = Math.max(1, maxBufferedChars);
        this.hardMaxBufferedChars = this.maxBufferedChars * 3;
        this.maxBufferAgeNanos = Math.max(0L, maxBufferAgeMillis) * 1_000_000L;
        this.nanoClock = nanoClock;
        this.bufferStartedNanos = nanoClock.getAsLong();
    }

    /**
     * Buffers one streamed token and speaks whatever that made ready - usually
     * nothing, since most tokens are mid-sentence.
     *
     * @param token the raw token as the model produced it, markdown and all
     * @param speak called zero or one time with normalized, ready-to-synthesize
     *     text; never called with blank text
     */
    public void accept(String token, Consumer<String> speak) {
        if (token == null || token.isEmpty()) {
            return;
        }
        if (buffer.length() == 0) {
            bufferStartedNanos = nanoClock.getAsLong();
        }
        buffer.append(token);
        int cut = flushIndex();
        if (cut > 0) {
            emit(cut, speak);
        }
    }

    /**
     * Speaks everything still buffered, unconditionally - the end of the turn.
     * Must be called from the stream's {@code onComplete} (and, if the caller
     * wants the partial answer spoken before its apology, its error handler);
     * must NOT be called on an interrupt.
     *
     * @param speak called zero or one time, as in {@link #accept}
     */
    public void flush(Consumer<String> speak) {
        if (buffer.length() > 0) {
            emit(buffer.length(), speak);
        }
    }

    /** @return whether anything is still held back (i.e. {@link #flush} would have something to say). */
    public boolean hasPending() {
        return buffer.length() > 0;
    }

    // ------------------------------------------------------------------------

    /** @return the exclusive end index of the prefix that is safe to speak now, or -1 to keep buffering. */
    private int flushIndex() {
        int len = buffer.length();
        if (len == 0 || insideOpenCodeFence()) {
            return -1;
        }
        int sentence = lastSentenceBoundary();
        if (sentence > 0) {
            return sentence;
        }
        if (len >= maxBufferedChars || bufferHasStoodTooLong()) {
            int word = lastWordBoundary();
            if (word > 0) {
                return word;
            }
            // No whitespace anywhere in a very long run (a giant URL, or a
            // script that does not use spaces) - cut it rather than grow
            // without bound. The only cut here that lands mid-word.
            if (len >= hardMaxBufferedChars) {
                return len;
            }
        }
        return -1;
    }

    private boolean bufferHasStoodTooLong() {
        return nanoClock.getAsLong() - bufferStartedNanos >= maxBufferAgeNanos;
    }

    /**
     * A newline anywhere, or a sentence terminator that is actually followed by
     * whitespace - the follow-on check is what keeps "3.14", "10:30" and
     * "v1.2" whole, since at the moment the terminator arrives we cannot yet
     * know whether it ends a sentence or sits inside a number.
     */
    private int lastSentenceBoundary() {
        for (int i = buffer.length() - 1; i >= 0; i--) {
            char c = buffer.charAt(i);
            if (c == '\n') {
                return pastWhitespace(i + 1);
            }
            if (SENTENCE_ENDERS.indexOf(c) >= 0
                && i + 1 < buffer.length()
                && Character.isWhitespace(buffer.charAt(i + 1))) {
                return pastWhitespace(i + 1);
            }
        }
        return -1;
    }

    /**
     * Takes the whitespace that separated the two sentences with the piece
     * being spoken rather than leaving it at the head of the buffer, where it
     * would be a "pending" piece that has nothing to say.
     */
    private int pastWhitespace(int from) {
        int i = from;
        while (i < buffer.length() && Character.isWhitespace(buffer.charAt(i))) {
            i++;
        }
        return i;
    }

    private int lastWordBoundary() {
        for (int i = buffer.length() - 1; i >= 0; i--) {
            if (Character.isWhitespace(buffer.charAt(i))) {
                return i + 1;
            }
        }
        return -1;
    }

    /**
     * An odd number of {@code ```}/{@code ~~~} fence lines means the buffer
     * currently ends inside a code block - see the class javadoc. Counting
     * whole lines (not bare occurrences) is what keeps {@code ~~struck~~} from
     * reading as a fence; a trailing line with no newline after it yet counts,
     * so an opener stops the flush as soon as it is complete.
     */
    private boolean insideOpenCodeFence() {
        int fences = 0;
        int lineStart = 0;
        int len = buffer.length();
        for (int i = 0; i <= len; i++) {
            if (i < len && buffer.charAt(i) != '\n') {
                continue;
            }
            int j = lineStart;
            while (j < i && (buffer.charAt(j) == ' ' || buffer.charAt(j) == '\t')) {
                j++;
            }
            if (i - j >= 3
                && (isRun(j, '`') || isRun(j, '~'))) {
                fences++;
            }
            lineStart = i + 1;
        }
        return fences % 2 == 1;
    }

    private boolean isRun(int from, char c) {
        return buffer.charAt(from) == c && buffer.charAt(from + 1) == c && buffer.charAt(from + 2) == c;
    }

    private void emit(int cut, Consumer<String> speak) {
        String piece = buffer.substring(0, cut);
        buffer.delete(0, cut);
        bufferStartedNanos = nanoClock.getAsLong();

        // Both flags are read off the RAW piece, before normalization trims
        // the whitespace that is exactly what they are asking about: a piece
        // cut at a word boundary ends in the space it was cut on, whereas the
        // hard-cap cut ends in the middle of a word.
        boolean continuesAWord = previousPieceEndedMidWord
            && !piece.isEmpty() && Character.isLetterOrDigit(piece.charAt(0));
        previousPieceEndedMidWord =
            !piece.isEmpty() && Character.isLetterOrDigit(piece.charAt(piece.length() - 1));

        String spoken = SpeechTextNormalizer.normalizeFragmentForSpeech(piece);
        if (spoken == null || spoken.isBlank()) {
            // The piece was pure markup (a stray fence, an emphasis marker on
            // its own). Saying nothing is the whole point of using the
            // fragment normalizer rather than normalizeForSpeech.
            return;
        }
        speak.accept(anythingSpoken && !continuesAWord ? " " + spoken : spoken);
        anythingSpoken = true;
    }
}
