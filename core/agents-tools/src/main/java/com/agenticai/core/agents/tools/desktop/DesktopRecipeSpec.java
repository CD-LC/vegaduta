package com.agenticai.core.agents.tools.desktop;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

/**
 * A "desktop recipe": the compact, replayable JSON form of a successful {@code local_desktop_*}
 * run - the action sequence (open, activate, press, click, type, key, wait) with per-step
 * verification, recorded once with AI and replayed deterministically later against the
 * desktop-agent sidecar on the person's laptop ("run once with AI, replay without it"). The
 * desktop analogue of {@code BrowserRecipeSpec}.
 *
 * <p>Placeholders only. Anything variable is a {@code {{param}}}, anything secret a {@code
 * {{secret.NAME}}} (see {@link DesktopRecipePlaceholders}); {@link DesktopRecipeValidator} refuses
 * a spec that uses a placeholder it cannot honour, so no credential or user-supplied value is
 * ever stored.
 *
 * <p>Absent fields are omitted from the JSON ({@code NON_NULL}) to keep a stored recipe small.
 *
 * @param version schema version, currently {@value #CURRENT_VERSION}
 * @param allowedApps applications the recipe may act in (lowercased names); every step's {@code
 *     app} - and every {@code appFrontmost} expectation - must be one of them. Empty means the
 *     recipe never names an app and acts only on whatever is frontmost.
 * @param params the parameters a caller must (or may) supply, by name - never their values
 * @param steps the ordered actions; the first must be {@code open} or {@code activate} (a replay
 *     starts cold, with no particular app in front)
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public record DesktopRecipeSpec(
        int version, List<String> allowedApps, List<ParamSpec> params, List<RecipeStep> steps) {

    public static final int CURRENT_VERSION = 1;

    public static final String OPEN = "open";
    public static final String ACTIVATE = "activate";
    public static final String PRESS = "press";
    public static final String CLICK = "click";
    public static final String TYPE = "type";
    public static final String KEY = "key";
    public static final String WAIT = "wait";

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ParamSpec(String name, boolean required) {}

    /**
     * What must be true after a step for the replay to continue; every set field must hold.
     * Verified through the sidecar's {@code POST /ui} (the accessibility tree of the frontmost
     * app), so an expectation never needs a screenshot or a model. Text may contain {@code
     * {{param}}} placeholders.
     *
     * @param elementLabel an interactive element whose label contains this (case-insensitive)
     *     must exist in the frontmost app's UI tree
     * @param appFrontmost the frontmost application's name must equal this (case-insensitive)
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Expect(String elementLabel, String appFrontmost) {

        @JsonIgnore
        public boolean isEmpty() {
            return isBlank(elementLabel) && isBlank(appFrontmost);
        }

        private static boolean isBlank(String s) {
            return s == null || s.isBlank();
        }
    }

    /**
     * One action. Only the fields its {@code action} uses are set; see {@link
     * DesktopRecipeValidator} for the shape each action requires.
     *
     * @param action {@code open}, {@code activate}, {@code press}, {@code click}, {@code type},
     *     {@code key} or {@code wait}
     * @param app activate: the application to bring to the front; press: the app to act in instead
     *     of the frontmost one; open: the {@code application} to open the target with. Always a
     *     literal member of {@code allowedApps} - a placeholder cannot choose which app is driven.
     * @param label press: text on the element to press (may contain {@code {{param}}})
     * @param role press: optional accessibility role, e.g. {@code AXButton}
     * @param x click: horizontal pixel from the screen's left edge
     * @param y click: vertical pixel from the top. (Replays always use the primary button - the
     *     step vocabulary has no button field.)
     * @param text type: the text to type (placeholders only for anything variable or secret)
     * @param key key: one named key - return, enter, tab, space, delete, escape, the arrows or
     *     f1-f12
     * @param target open: the app name, file path or URL to open (may contain {@code {{param}}})
     * @param waitMs wait: how long to pause, in milliseconds
     * @param expect verification after the step (optional on every action)
     * @param timeoutMs per-step verification budget; null = the platform default
     * @param irreversible true for a step that can submit/pay/delete/send: replay stops BEFORE it
     *     unless the caller explicitly confirmed irreversible steps
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record RecipeStep(
            String action,
            String app,
            String label,
            String role,
            Integer x,
            Integer y,
            String text,
            String key,
            String target,
            Integer waitMs,
            Expect expect,
            Integer timeoutMs,
            boolean irreversible) {}
}
