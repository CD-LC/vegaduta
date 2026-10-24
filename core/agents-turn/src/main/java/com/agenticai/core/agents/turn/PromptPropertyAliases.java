package com.agenticai.core.agents.turn;

import com.agenticai.core.prompts.PromptDefaultAlias;
import java.util.Locale;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The deprecated {@code app.*} properties that used to hold model-facing text now owned by the prompt
 * registry. Each is a {@link PromptDefaultAlias}: a value the operator changed from the shipped text is
 * applied as the PLATFORM-scope default of the key (below every stored override, replacing only the
 * shipped file), and a warning names the property and the key to edit instead. A property that still
 * holds the shipped text, or nothing, changes nothing.
 */
final class PromptPropertyAliases {

    private static final Logger log = LoggerFactory.getLogger(PromptPropertyAliases.class);

    private PromptPropertyAliases() {}

    /** {@code app.chat.finish.continue-nudge} -> {@code nudge.reply_cut_off}. */
    static PromptDefaultAlias cutOff(ChatFinishProperties properties) {
        return single(
                NudgeTexts.REPLY_CUT_OFF,
                "app.chat.finish.continue-nudge",
                properties == null ? null : properties.getContinueNudge());
    }

    /** {@code app.chat.finish.retry-tool-call-nudge} -> {@code nudge.retry_tool_call}. */
    static PromptDefaultAlias retryToolCall(ChatFinishProperties properties) {
        return single(
                NudgeTexts.RETRY_TOOL_CALL,
                "app.chat.finish.retry-tool-call-nudge",
                properties == null ? null : properties.getRetryToolCallNudge());
    }

    /** {@code app.chat.finish.truncated-notice} -> {@code message.finish.truncated}. */
    static PromptDefaultAlias truncatedNotice(ChatFinishProperties properties) {
        return single(
                ReplyFinish.TRUNCATED_KEY,
                "app.chat.finish.truncated-notice",
                properties == null ? null : properties.getTruncatedNotice());
    }

    /** {@code app.chat.finish.refusal-notice} -> {@code message.finish.refusal}. */
    static PromptDefaultAlias refusalNotice(ChatFinishProperties properties) {
        return single(
                ReplyFinish.REFUSAL_KEY,
                "app.chat.finish.refusal-notice",
                properties == null ? null : properties.getRefusalNotice());
    }

    /** {@code app.chat.finish.malformed-tool-call-notice} -> {@code message.finish.malformed_tool_call}. */
    static PromptDefaultAlias malformedToolCallNotice(ChatFinishProperties properties) {
        return single(
                ReplyFinish.MALFORMED_TOOL_CALL_KEY,
                "app.chat.finish.malformed-tool-call-notice",
                properties == null ? null : properties.getMalformedToolCallNotice());
    }

    /** {@code app.chat.finish.empty-reply-message} -> {@code message.finish.empty_reply}. */
    static PromptDefaultAlias emptyReplyMessage(ChatFinishProperties properties) {
        return single(
                ReplyFinish.EMPTY_REPLY_KEY,
                "app.chat.finish.empty-reply-message",
                properties == null ? null : properties.getEmptyReplyMessage());
    }

    /** {@code app.chat.continuation.nudge.<lang>} -> {@code nudge.continue_unfinished}. */
    static PromptDefaultAlias continueUnfinished(ContinuationProperties properties) {
        return new ContinuationNudge(properties == null ? new ContinuationProperties() : properties);
    }

    /** One text for every language: used when it is not the shipped one. */
    private static PromptDefaultAlias single(String key, String property, String value) {
        String shipped = NudgeTexts.shipped(key);
        String applied = value == null || value.isBlank() || value.equals(shipped) ? null : value;
        if (value != null && value.isBlank()) {
            log.warn("{} is blank and is ignored; the shipped text of prompt '{}' applies", property, key);
        } else if (applied != null) {
            deprecated(property, key);
        }
        return new PromptDefaultAlias() {
            @Override
            public String key() {
                return key;
            }

            @Override
            public String text(String locale) {
                return applied;
            }
        };
    }

    private static void deprecated(String property, String key) {
        log.warn(
                "{} is deprecated and will be removed: its value is applied as the platform default of the prompt '{}'."
                        + " Edit that prompt (Admin > Instructions > All prompts, platform scope) and remove the property.",
                property,
                key);
    }

    /** The per-language nudge map; a language with no entry falls back to the entry of the default language. */
    private static final class ContinuationNudge implements PromptDefaultAlias {

        private final ContinuationProperties properties;

        ContinuationNudge(ContinuationProperties properties) {
            this.properties = properties;
            for (Map.Entry<String, String> e : properties.getNudge().entrySet()) {
                String property = "app.chat.continuation.nudge." + e.getKey();
                if (e.getValue() == null || e.getValue().isBlank()) {
                    log.warn("{} is blank and is ignored; the shipped text of prompt '{}' applies", property, key());
                } else if (!e.getValue().equals(NudgeTexts.shippedExact(NudgeTexts.CONTINUE_UNFINISHED, e.getKey()))) {
                    deprecated(property, key());
                }
            }
        }

        @Override
        public String key() {
            return NudgeTexts.CONTINUE_UNFINISHED;
        }

        @Override
        public String text(String locale) {
            Map<String, String> nudges = properties.getNudge();
            String tag = locale == null || locale.isBlank() ? properties.getDefaultLocale() : locale;
            String from = tag;
            String value = nudges.get(tag);
            if (value == null) {
                from = properties.getDefaultLocale();
                value = nudges.get(from);
            }
            if (value == null || value.isBlank() || value.equals(NudgeTexts.shippedExact(key(), from))) {
                return null;
            }
            return value;
        }
    }

    /**
     * {@code app.chat.escalation.low-confidence-markers} (a comma-separated list) -> {@code
     * detector.escalation.low_confidence_markers} (one marker per line).
     */
    static PromptDefaultAlias lowConfidenceMarkers(String csv) {
        String lines = markerLines(csv);
        if (lines != null) {
            deprecated("app.chat.escalation.low-confidence-markers", VerifierEscalationRouter.LOW_CONFIDENCE_MARKERS_KEY);
        }
        return new PromptDefaultAlias() {
            @Override
            public String key() {
                return VerifierEscalationRouter.LOW_CONFIDENCE_MARKERS_KEY;
            }

            @Override
            public String text(String locale) {
                return lines;
            }
        };
    }

    private static String markerLines(String csv) {
        if (csv == null || csv.isBlank()) {
            return null;
        }
        StringBuilder out = new StringBuilder();
        for (String m : csv.split(",")) {
            String t = m.trim().toLowerCase(Locale.ROOT);
            if (!t.isEmpty()) {
                out.append(out.length() == 0 ? "" : "\n").append(t);
            }
        }
        return out.length() == 0 ? null : out.toString();
    }
}
