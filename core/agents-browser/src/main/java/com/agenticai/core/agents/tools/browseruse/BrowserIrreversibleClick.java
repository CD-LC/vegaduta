package com.agenticai.core.agents.tools.browseruse;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * A deliberately conservative heuristic for "this click probably cannot be undone": a purchase, a
 * payment, a confirmation, a form submit, a deletion, a send. The tool-classification gate works on
 * tool NAMES, not arguments, so {@code browser_click_element} (classified EGRESS but not
 * irreversible) refuses a click on such an element and tells the model to use {@code
 * browser_confirm_click}, whose classification is irreversible and therefore routes through the
 * human-approval gate.
 *
 * <p>The decision is made from the role and accessible name the SERVER recorded in the snapshot,
 * never from anything the model supplies at click time, so a model cannot talk its way past it by
 * passing a friendlier label. A false positive costs one human approval; a false negative is a
 * purchase nobody confirmed - so the word list leans broad. Prefix-matching on word starts ({@code
 * confirm} covers "Confirmation", {@code delet} covers "Delete"/"Deleting") on purpose.
 *
 * <p>Links are held to a narrower list than buttons: "Orders" or "Send feedback" as navigation
 * links are ordinary, while "Buy now" or "Pay" as a link is a purchase.
 */
final class BrowserIrreversibleClick {

    private BrowserIrreversibleClick() {}

    private static final Pattern BUTTON_WORDS =
            Pattern.compile(
                    "\\b(buy|purchas|pay|checkout|check out|place order|order|confirm|submit|delet|erase|send|transfer|withdraw|"
                            + "donat|subscrib|publish|approv|authori[sz])",
                    Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    private static final Pattern LINK_WORDS =
            Pattern.compile(
                    "\\b(buy|purchas|pay|checkout|check out|place order|confirm|delet)",
                    Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    /**
     * True when clicking this element looks like a purchase / payment / confirmation / submit /
     * delete / send.
     */
    static boolean isIrreversible(String role, String name) {
        if (name == null || name.isBlank()) {
            return false;
        }
        String r = role == null ? "" : role.toLowerCase(Locale.ROOT);
        String n = name.strip();
        return switch (r) {
            case "link" -> LINK_WORDS.matcher(n).find();
            case "button",
                    "menuitem",
                    "clickable",
                    "tab",
                    "option",
                    "menuitemcheckbox",
                    "menuitemradio" ->
                    BUTTON_WORDS.matcher(n).find();
            default -> false;
        };
    }

    /** The model-facing text returned instead of clicking a flagged element. */
    static String refusalNote(String elementLabel) {
        return "Element "
                + elementLabel
                + " looks like a purchase, payment, confirmation, submit, delete or send action, "
                + "so it was NOT clicked. Only if the user explicitly asked for exactly this, call browser_confirm_click with the "
                + "same index and label - a human must approve it. Otherwise do not click it.";
    }

    /**
     * True when the model's {@code label} argument IS the element's recorded {@code name}, tolerant
     * only of case, spacing and apostrophe style. Equality, not containment: the approver reads the
     * model-supplied label, so a fragment ("Pay") or a longer misleading string ("Don't pay") must
     * not pass for a different button.
     */
    static boolean labelMatches(String recordedName, String suppliedLabel) {
        String a = normalise(recordedName);
        String b = normalise(suppliedLabel);
        if (a.isEmpty() || b.isEmpty()) {
            return false;
        }
        return a.equals(b);
    }

    private static String normalise(String s) {
        return s == null
                ? ""
                : s.toLowerCase(Locale.ROOT).replaceAll("[\\s\\u2019']+", " ").strip();
    }
}
