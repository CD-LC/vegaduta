package com.agenticai.core.agents.turn;

import com.agenticai.core.prompts.PromptCatalog;
import com.agenticai.core.prompts.PromptResolver;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The three model-facing nudges of a turn: registry keys whose shipped files are the only copy of the
 * wording. The deprecated {@code app.chat.continuation.nudge.<lang>} and {@code
 * app.chat.finish.continue-nudge} / {@code retry-tool-call-nudge} properties default to these texts
 * (see {@link PromptPropertyAliases}); a call site never reads them, it asks the resolver.
 */
final class NudgeTexts {

    /** Sent when a reply stopped at a step it announced and never took. */
    static final String CONTINUE_UNFINISHED = "nudge.continue_unfinished";

    /** Sent when a reply hit the output length limit. */
    static final String REPLY_CUT_OFF = "nudge.reply_cut_off";

    /** Sent when the model's last tool call was unusable. */
    static final String RETRY_TOOL_CALL = "nudge.retry_tool_call";

    private NudgeTexts() {}

    private static PromptCatalog catalog() {
        return PromptResolver.defaultsOnly().catalog();
    }

    /** The shipped text of {@code key} in the default locale. */
    static String shipped(String key) {
        return catalog().defaultText(key);
    }

    /** The shipped text of {@code key} in {@code locale}, or null when no file is shipped for exactly that locale. */
    static String shippedExact(String key, String locale) {
        PromptCatalog catalog = catalog();
        return locale != null && catalog.locales(key).contains(locale)
                ? catalog.defaultText(key, locale).orElse(null)
                : null;
    }

    /** Every shipped text of {@code key} by language tag. */
    static Map<String, String> shippedByLocale(String key) {
        PromptCatalog catalog = catalog();
        Map<String, String> out = new LinkedHashMap<>();
        for (String locale : catalog.locales(key)) {
            catalog.defaultText(key, locale).ifPresent(text -> out.put(locale, text));
        }
        return out;
    }
}
