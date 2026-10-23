package com.agenticai.core.agents.turn;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Decides whether a finished model reply is really a stopped task: the model wrote what it is about to
 * do ("Let me create the pom.xml:") and then returned text with no tool call, which ends the provider's
 * tool loop. Small local models do this routinely; the turn then looks finished to the framework while
 * the work is not. The turn re-prompts the model instead of handing the half-done reply back - one policy
 * for every reasoning depth, applied by {@link FastStreamContinuation} (streamed) and {@link
 * BufferedTurnContinuation} (CAREFUL, RIGOROUS, PLANNED).
 *
 * <p>The decision starts from signals the platform can observe ({@link Signals}) and falls back to the
 * wording of the reply only for a model that is a weak tool caller ({@link
 * ContinuationProperties#getHeuristicScope()}):
 *
 * <ul>
 *   <li>a turn parked on a human approval is never continued: every tool is held until the human decides,
 *       so a nudge to "call the tools" can only be answered with more refusals - asked of the guard's
 *       explicit record ({@code TurnApprovalState#isParked}), never guessed from how the reply is worded;
 *   <li>a pass after the first must have called a tool (counted by {@code TurnToolActivity}), else the
 *       model ignored the nudge and asking again changes nothing;
 *   <li>a reply that ended for a reason the provider reported (cut off, refused, ...) is dealt with by the
 *       finish-reason handling and never reaches this class.
 * </ul>
 *
 * <p>The wording check ({@link #looksUnfinished}) reads the last sentence only: an announced step is the
 * final thing the model said, and "I will summarise below. ... The build is green." is a finished answer.
 * A sentence that offers further help ("Let me know if you need anything else") is a closing, not an
 * announced step. The patterns are regular expressions per language ({@link ContinuationProperties}); a
 * reply is checked against every configured language and the one that matched picks the nudge.
 */
final class TurnContinuation {

    /** What the turn knows when a pass has completed normally. */
    record Signals(
            String reply,
            boolean weakToolCaller,
            boolean parked,
            int passesRun,
            int toolCallsInLastPass) {}

    /** Whether to run another pass, why, and the language of the nudge. */
    record Decision(boolean proceed, String reason, String locale) {
        static Decision stop(String reason) {
            return new Decision(false, reason, null);
        }
    }

    private static final Pattern SENTENCE_BREAK = Pattern.compile("(?<=[.!?。！？।؟])\\s*|\\R+");

    private final ContinuationProperties properties;
    private final Map<String, Pattern> intent;
    private final Map<String, Pattern> closing;
    private final Map<String, Pattern> done;
    private final Map<String, Pattern> asks;
    private final Map<String, Pattern> unfinishedEnding;

    TurnContinuation(ContinuationProperties properties) {
        this.properties = properties == null ? new ContinuationProperties() : properties;
        this.intent = compile(this.properties.getIntent());
        this.closing = compile(this.properties.getClosing());
        this.done = compile(this.properties.getDone());
        this.asks = compile(this.properties.getAsks());
        this.unfinishedEnding = compile(this.properties.getUnfinishedEnding());
    }

    private static Map<String, Pattern> compile(Map<String, String> byLocale) {
        Map<String, Pattern> out = new LinkedHashMap<>();
        if (byLocale != null) {
            byLocale.forEach(
                    (locale, regex) ->
                            out.put(
                                    locale,
                                    Pattern.compile(
                                            regex,
                                            Pattern.CASE_INSENSITIVE
                                                    | Pattern.UNICODE_CASE
                                                    | Pattern.UNICODE_CHARACTER_CLASS)));
        }
        return out;
    }

    /** True for a bring-your-own provider listed in {@code app.chat.continuation.heuristic-providers} (local servers). */
    boolean isHeuristicProvider(com.agenticai.core.providers.ProviderName provider) {
        return provider != null && properties.getHeuristicProviders().contains(provider);
    }

    /** The catalog key of the switch for the wording check (platform value {@code app.chat.continuation.heuristic-enabled}). */
    static final String HEURISTIC_ENABLED_KEY = "chat.continuation.heuristicEnabled";

    /** The platform value of the wording-check switch, for the use site that asks the catalog for an override. */
    boolean heuristicEnabledByPlatform() {
        return properties.isHeuristicEnabled();
    }

    /** True when the wording check may run for a model with these traits. */
    boolean heuristicApplies(boolean weakToolCaller) {
        return heuristicApplies(weakToolCaller, properties.isHeuristicEnabled());
    }

    /** As {@link #heuristicApplies(boolean)} with the switch's value for this turn's tenant and agent. */
    boolean heuristicApplies(boolean weakToolCaller, boolean heuristicEnabled) {
        return heuristicEnabled
                && (weakToolCaller
                        || properties.getHeuristicScope() == ContinuationProperties.Scope.ALL_MODELS);
    }

    Decision decide(Signals s) {
        return decide(s, properties.isHeuristicEnabled());
    }

    /** As {@link #decide(Signals)} with the wording-check switch's value for this turn's tenant and agent. */
    Decision decide(Signals s, boolean heuristicEnabled) {
        if (s.parked()) {
            return Decision.stop("the turn is parked on a human approval");
        }
        if (!heuristicApplies(s.weakToolCaller(), heuristicEnabled)) {
            return Decision.stop("the reply is an answer: the wording check does not apply to this model");
        }
        if (s.passesRun() > 0 && s.toolCallsInLastPass() == 0) {
            return Decision.stop("the last continuation pass called no tool");
        }
        String locale = unfinishedLocale(s.reply());
        return locale == null
                ? Decision.stop("the reply does not read as an announced step")
                : new Decision(true, "the reply stops at an announced step", locale);
    }

    /**
     * The nudge for {@code locale}: the prompt {@code nudge.continue_unfinished} resolved in that language for the
     * turn's agent and tenant (an override of the agent, the tenant or the platform, else the shipped text of the
     * language, else of the default language).
     */
    String nudge(String locale, ResolvedDirectives prompts) {
        return (prompts == null ? ResolvedDirectives.DEFAULTS : prompts).withLocale(locale).text(NudgeTexts.CONTINUE_UNFINISHED);
    }

    /** True when the wording of {@code reply} reads as a step announced and not taken. */
    boolean looksUnfinished(String reply) {
        return unfinishedLocale(reply) != null;
    }

    /** The language whose patterns matched an unfinished reply, or {@code null} when it reads as finished. */
    String unfinishedLocale(String reply) {
        if (reply == null) {
            return null;
        }
        String text = reply.replace('’', '\'').replace('‘', '\'').strip();
        if (text.isEmpty() || anyMatches(asks, text) != null) {
            return null;
        }
        String tail =
                text.length() > properties.getTailChars()
                        ? text.substring(text.length() - properties.getTailChars())
                        : text;
        String ending = anyMatches(unfinishedEnding, text);
        if (ending != null) {
            return ending;
        }
        if (anyMatches(done, tail) != null) {
            return null;
        }
        String sentence = lastSentence(tail);
        if (anyMatches(closing, sentence) != null) {
            return null;
        }
        return anyMatches(intent, sentence);
    }

    private static String lastSentence(String tail) {
        String last = tail;
        for (String piece : SENTENCE_BREAK.split(tail)) {
            if (!piece.isBlank()) {
                last = piece;
            }
        }
        return last.strip().toLowerCase(Locale.ROOT);
    }

    /** The first language whose pattern is found in {@code text}, or {@code null}. */
    private static String anyMatches(Map<String, Pattern> patterns, String text) {
        for (Map.Entry<String, Pattern> entry : patterns.entrySet()) {
            if (entry.getValue().matcher(text).find()) {
                return entry.getKey();
            }
        }
        return null;
    }
}
