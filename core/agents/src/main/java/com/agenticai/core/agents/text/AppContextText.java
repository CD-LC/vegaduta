package com.agenticai.core.agents.text;

/**
 * The {@code <app_context>...</app_context>} block the console puts ahead of what the person typed
 * (what is connected, the attached folder - written by the app, for the model). Not the person's
 * words, so anywhere a human-facing label is derived from the raw message - a thread title, an
 * approval card's "task it was working on" - the block is stripped first. Kernel-side leaf so both
 * the turn pipeline and the branching store can share it without a package edge between them.
 */
public final class AppContextText {

    public static final String OPEN = "<app_context>";

    public static final String CLOSE = "</app_context>";

    private AppContextText() {}

    /**
     * {@code text} without a leading app-context block. A block that is opened and never closed is
     * all app text, so nothing of it is kept.
     */
    public static String withoutAppContext(String text) {
        String trimmed = text == null ? "" : text.stripLeading();
        if (!trimmed.startsWith(OPEN)) {
            return text == null ? "" : text;
        }
        int close = trimmed.indexOf(CLOSE);
        return close < 0 ? "" : trimmed.substring(close + CLOSE.length());
    }
}
