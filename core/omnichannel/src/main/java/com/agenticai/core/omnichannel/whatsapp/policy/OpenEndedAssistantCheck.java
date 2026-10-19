package com.agenticai.core.omnichannel.whatsapp.policy;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * A keyword heuristic that flags an agent which looks like an open-ended, general-purpose
 * assistant. WhatsApp's Business Solution Terms (section 4.7) restrict products whose primary
 * function is such an assistant; this check only produces a warning. It cannot predict Meta's
 * decision, and a false positive is overridable.
 */
public final class OpenEndedAssistantCheck {

    private static final int MIN_PROMPT_CHARS = 40;

    private static final List<Pattern> OPEN_ENDED_PHRASES =
            List.of(
                    Pattern.compile("general[- ]purpose"),
                    Pattern.compile("\\b(answer|help with) (any|all|every)(thing| question| topic)"),
                    Pattern.compile("anything (the user|you|they) (asks?|needs?|wants?)"),
                    Pattern.compile("\\bany (topic|subject)\\b"),
                    Pattern.compile("\\bversatile\\b"),
                    Pattern.compile("deep research|research assistant"),
                    Pattern.compile("no restrictions|without restrictions"),
                    Pattern.compile("you are (chatgpt|a helpful assistant|an ai assistant)\\b"));

    private static final List<String> TASK_WORDS =
            List.of(
                    "support",
                    "customer",
                    "booking",
                    "appointment",
                    "reservation",
                    "order",
                    "lead",
                    "reminder",
                    "payment",
                    "invoice",
                    "front desk",
                    "enquir",
                    "inquir");

    private static final List<String> SCOPE_LIMITERS =
            List.of(
                    "only answer",
                    "only help",
                    "only discuss",
                    "do not answer",
                    "don't answer",
                    "out of scope",
                    "outside of",
                    "politely decline",
                    "politely refuse",
                    "stay on topic",
                    "stay within",
                    "decline anything",
                    "decline questions");

    private static final List<String> BROAD_TOOL_MARKERS =
            List.of("web_search", "search", "browse", "deep_research", "code", "execute", "run_");

    private OpenEndedAssistantCheck() {}

    public record Assessment(boolean openEnded, List<String> reasons) {}

    /**
     * @param systemPrompt the agent's system prompt; may be null
     * @param allowedToolNames the agent's tool allow-list; empty means unrestricted
     */
    public static Assessment assess(String systemPrompt, Collection<String> allowedToolNames) {
        String prompt = systemPrompt == null ? "" : systemPrompt.toLowerCase(Locale.ROOT);
        List<String> reasons = new ArrayList<>();

        boolean tooShort = prompt.strip().length() < MIN_PROMPT_CHARS;
        boolean hasTaskWord = TASK_WORDS.stream().anyMatch(prompt::contains);
        boolean hasLimiter = SCOPE_LIMITERS.stream().anyMatch(prompt::contains);
        boolean openPhrase = OPEN_ENDED_PHRASES.stream().anyMatch(p -> p.matcher(prompt).find());

        boolean noScope = tooShort || (!hasTaskWord && !hasLimiter);
        if (tooShort) {
            reasons.add("The system prompt is empty or too short to define a task.");
        } else if (noScope) {
            reasons.add("The system prompt names no business task (support, booking, orders...).");
        }
        if (openPhrase && !hasLimiter) {
            reasons.add(
                    "The system prompt describes a general-purpose assistant and does not limit"
                            + " what it will discuss.");
        }

        long broadTools =
                allowedToolNames == null
                        ? 0
                        : allowedToolNames.stream()
                                .map(n -> n.toLowerCase(Locale.ROOT))
                                .filter(n -> BROAD_TOOL_MARKERS.stream().anyMatch(n::contains))
                                .count();
        boolean broadToolkit = broadTools >= 2 && !hasLimiter;
        if (broadToolkit) {
            reasons.add(
                    "The agent has several open-ended tools (search, browsing, code) and no scope"
                            + " limit.");
        }

        boolean openEnded = noScope || (openPhrase && !hasLimiter) || broadToolkit;
        return new Assessment(openEnded, List.copyOf(reasons));
    }
}
