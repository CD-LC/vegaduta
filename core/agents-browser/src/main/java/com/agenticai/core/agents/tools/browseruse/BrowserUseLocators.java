package com.agenticai.core.agents.tools.browseruse;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.PlaywrightException;
import java.util.function.Consumer;

/**
 * Turns {@code browser_click}/{@code browser_type}'s {@code selector} argument - which the tool
 * descriptions deliberately accept as EITHER a real CSS/Playwright selector OR a plain-language
 * description of the element ("the Submit button", "search box") - into a Playwright action, the
 * same "be liberal about what an LLM sends" posture the rest of this package's tool arguments take
 * (see {@code DeviceToolSupport#parseArgs}'s tolerant JSON parsing).
 *
 * <p>Models are not reliable at producing exact CSS/XPath, so requiring a real selector would make
 * these two tools fail on the majority of realistic calls. {@link #performWithFallback} is the one
 * entry point both {@code BrowserUseSessionManager#click}/{@code #type} use, and it resolves in two
 * steps:
 *
 * <ol>
 *   <li>If the value has an explicit Playwright selector engine prefix ({@code css=}, {@code
 *       xpath=}, {@code text=}, {@code role=}, {@code id=}) or LOOKS like a bare CSS selector
 *       ({@link #isSelectorAttempt}), it is tried as a selector first.
 *   <li>If that attempt throws a {@link PlaywrightException} (no match, invalid selector syntax,
 *       strict-mode violation) - or the value never looked like a selector to begin with - it is
 *       retried as visible text via {@link Page#getByText}, case-insensitive substring match, which
 *       is the natural reading of a plain-language description like "the Submit button".
 * </ol>
 *
 * A value that never looked like a selector goes straight to step 2 (no wasted attempt); a value
 * that DID look like one but was actually a coincidentally space-free description ({@code
 * "LoginButton"}) still resolves via the retry. Either way, the FINAL failure the model sees is
 * whatever step 2 raised, which is the more informative one for a plain-language mistake.
 */
final class BrowserUseLocators {

    private BrowserUseLocators() {}

    /**
     * Performs {@code action} against the element {@code selectorOrDescription} resolves to, with
     * the selector-then-text fallback described in this class's javadoc. Throws whatever {@link
     * PlaywrightException} the LAST attempt raised (or a {@link BrowserUseException} for a blank
     * value).
     */
    static void performWithFallback(
            Page page, String selectorOrDescription, Consumer<Locator> action) {
        if (selectorOrDescription == null || selectorOrDescription.isBlank()) {
            throw new BrowserUseException("no selector or element description was given");
        }
        String value = selectorOrDescription.strip();
        if (!isSelectorAttempt(value)) {
            action.accept(byText(page, value));
            return;
        }
        try {
            action.accept(page.locator(value));
        } catch (PlaywrightException e) {
            action.accept(byText(page, value));
        }
    }

    private static Locator byText(Page page, String value) {
        return page.getByText(value, new Page.GetByTextOptions().setExact(false));
    }

    /**
     * True when {@code value} is worth trying as a real Playwright selector before falling back to
     * a text match - see class javadoc. A false positive only costs one extra Playwright call
     * before the text fallback kicks in, so this is deliberately permissive rather than a strict
     * CSS grammar check.
     */
    private static boolean isSelectorAttempt(String value) {
        if (value.startsWith("css=")
                || value.startsWith("xpath=")
                || value.startsWith("text=")
                || value.startsWith("role=")
                || value.startsWith("id=")) {
            return true;
        }
        if (value.startsWith("#")
                || value.startsWith(".")
                || value.startsWith("[")
                || value.startsWith(">")) {
            return true;
        }
        // No whitespace and only selector-safe characters - plausibly a bare
        // tag/class/id/combinator selector like "button.primary" or "input#email".
        if (value.chars().anyMatch(Character::isWhitespace)) {
            return false;
        }
        return value.chars()
                .allMatch(
                        c ->
                                Character.isLetterOrDigit(c)
                                        || c == '.'
                                        || c == '#'
                                        || c == '-'
                                        || c == '_'
                                        || c == '>'
                                        || c == '['
                                        || c == ']'
                                        || c == '='
                                        || c == '"'
                                        || c == '\''
                                        || c == ':');
    }
}
