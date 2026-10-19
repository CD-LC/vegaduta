package com.agenticai.core.agents.tools.browseruse;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

/**
 * A "browser recipe": the compact, replayable JSON form of a successful browser task - the action
 * sequence (navigate, click, type, select, wait_for) with per-step verification, recorded once with
 * AI and replayed deterministically later ("run once with AI, replay without it; AI only on
 * failure").
 *
 * <p>Placeholders only. Anything variable is a {@code {{param}}}, anything secret a {@code
 * {{secret.NAME}}} or {@code {{totp.NAME}}} (see {@link BrowserRecipePlaceholders}); {@link
 * BrowserRecipeValidator} refuses a spec that types a literal into a sensitive field, so no
 * credential or user-supplied value is ever stored.
 *
 * <p>Absent fields are omitted from the JSON ({@code NON_NULL}) to keep a stored recipe small.
 *
 * @param version schema version, currently {@value #CURRENT_VERSION}
 * @param allowedDomains hosts the recipe may navigate to / stay on; every step's page must remain
 *     inside them
 * @param params the parameters a caller must (or may) supply, by name - never their values
 * @param steps the ordered actions; the first must be a {@code navigate}
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public record BrowserRecipeSpec(
        int version, List<String> allowedDomains, List<ParamSpec> params, List<RecipeStep> steps) {

    public static final int CURRENT_VERSION = 1;

    public static final String NAVIGATE = "navigate";
    public static final String CLICK = "click";
    public static final String TYPE = "type";
    public static final String SELECT = "select";
    public static final String WAIT_FOR = "wait_for";

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ParamSpec(String name, boolean required) {}

    /**
     * What must be true after a step for the replay to continue. Every set field must hold.
     * Text/URL values may contain {@code {{param}}} placeholders.
     *
     * @param urlHost the page's host must equal this (case-insensitive)
     * @param urlPathPrefix the page's path must start with this
     * @param urlContains the full URL must contain this
     * @param textContains the visible page text must contain this (case-insensitive), waited for up
     *     to the timeout
     * @param selector an element matching this selector (or text description) must appear within
     *     the timeout
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Expect(
            String urlHost,
            String urlPathPrefix,
            String urlContains,
            String textContains,
            String selector) {

        @JsonIgnore
        public boolean isEmpty() {
            return isBlank(urlHost)
                    && isBlank(urlPathPrefix)
                    && isBlank(urlContains)
                    && isBlank(textContains)
                    && isBlank(selector);
        }

        private static boolean isBlank(String s) {
            return s == null || s.isBlank();
        }
    }

    /**
     * One action.
     *
     * @param action {@code navigate}, {@code click}, {@code type}, {@code select} or {@code
     *     wait_for}
     * @param url navigate: the URL (may contain {@code {{param}}}, URL-encoded at replay)
     * @param selector click/type/select: a CSS/Playwright selector or a plain description, exactly
     *     as the live {@code browser_click}/{@code browser_type} tools accept
     * @param text type: the text to enter (placeholders only for anything variable or secret)
     * @param value select: the option value/label to choose
     * @param expect verification after the step (optional for click/type/select/navigate, required
     *     for wait_for)
     * @param timeoutMs per-step verification budget; null = the platform default
     * @param irreversible true for a step that can submit/buy/delete/send: replay stops BEFORE it
     *     unless the caller explicitly confirmed irreversible steps
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record RecipeStep(
            String action,
            String url,
            String selector,
            String text,
            String value,
            Expect expect,
            Integer timeoutMs,
            boolean irreversible) {}
}
