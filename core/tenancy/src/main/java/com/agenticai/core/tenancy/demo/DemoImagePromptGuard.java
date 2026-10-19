package com.agenticai.core.tenancy.demo;

import java.text.Normalizer;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * A cheap, local, pre-flight refusal check on an anonymous visitor's image
 * prompt - run BEFORE any money is spent at the provider.
 *
 * <h2>What this is, and what it very deliberately is not</h2>
 * This is <b>not</b> a content-safety system. Google's own safety filters on
 * the generation endpoint are that, and they stay the real control - the
 * {@code blockReason} they return is handled as a refusal by the caller. What
 * this class is, is a <b>cost gate with a nicer error message</b>: a keyword
 * screen that catches the blatant cases (sexual content, gore, weapons,
 * likenesses of real people) so the platform neither pays for a call that was
 * always going to be refused upstream, nor makes the visitor wait eight
 * seconds to find out.
 *
 * <p>Saying that plainly matters, because a keyword list is trivially evaded
 * by anyone who wants to and would be actively misleading if it were
 * presented as protection. The honest layering is: this catches the careless,
 * the provider catches the deliberate, and the 2-image per-sandbox cap bounds
 * what any of it can cost.
 *
 * <h2>Why "people" is on the list at all</h2>
 * Not squeamishness - it is the single highest-risk category for an
 * unauthenticated, unattributable endpoint. Generated likenesses of real,
 * named individuals (politicians especially, in an Indian election context)
 * are the one output from this feature that could plausibly cause real-world
 * harm and be traced back to the platform, and there is no product reason a
 * "see what our agents can do" sandbox needs to make them. Product imagery,
 * logos, illustrations and scenes - what the feature is actually for - are
 * entirely unaffected.
 */
final class DemoImagePromptGuard {

    /**
     * Word-boundary matched, lower-cased, accent-stripped. Kept as whole words
     * on purpose: a naive {@code contains()} on a list like this produces the
     * classic false positives ("assessment", "shooting a video", "Scunthorpe")
     * and a demo that refuses innocuous prompts is worse than one that
     * occasionally passes a bad one through to the provider's own filter.
     */
    private static final Set<String> BLOCKED_TERMS = Set.of(
        // Sexual / nudity
        "nude", "nudes", "nudity", "naked", "topless", "nsfw", "porn", "porno",
        "pornographic", "erotic", "erotica", "sexual", "sexy", "lingerie",
        "explicit", "fetish", "hentai", "boobs", "genitals", "nipples",
        // Minors in any of the above context - blocked outright, no nuance attempted
        "child", "children", "kid", "kids", "minor", "teen", "teens", "underage",
        "toddler", "schoolgirl", "schoolboy",
        // Violence / gore
        "gore", "gory", "beheading", "decapitated", "mutilated", "mutilation",
        "torture", "massacre", "corpse", "corpses", "bloodbath", "dismembered",
        "execution", "lynching", "suicide", "selfharm",
        // Weapons / terror
        "gun", "guns", "rifle", "pistol", "firearm", "firearms", "bomb", "bombs",
        "grenade", "explosive", "explosives", "terrorist", "terrorism", "isis",
        // Real-person likenesses (see class javadoc). Generic terms only - an
        // enumerated celebrity list would be both endless and stale.
        "celebrity", "politician", "president", "prime minister", "deepfake",
        "lookalike", "impersonate", "impersonation",
        // Hate
        "nazi", "swastika", "kkk", "genocide"
    );

    /**
     * Multi-word entries above ("prime minister") cannot be matched by a
     * single-token split, so the whole check runs as one alternation regex
     * with {@code \b} boundaries instead.
     */
    private static final Pattern BLOCKED_PATTERN = compile(BLOCKED_TERMS);

    private DemoImagePromptGuard() {
    }

    /**
     * @param extraTerms operator-supplied additions from the {@code
     *     demo.image.blocked-terms} platform setting (comma separated) - so a
     *     term that starts being abused can be blocked without a redeploy,
     *     the same no-redeploy posture every other demo limit takes.
     * @return {@code null} when the prompt is acceptable, otherwise the
     *     visitor-facing refusal sentence. Deliberately one generic sentence
     *     that never names the matched term: echoing it back turns this into
     *     an oracle for probing the list, and it reads as scolding.
     */
    static String refusalFor(String prompt, String extraTerms) {
        String normalized = normalize(prompt);
        if (normalized.isBlank()) {
            return "Tell the agent what to draw - for example \"a warm, minimal logo for a filter-coffee stall\".";
        }
        if (BLOCKED_PATTERN.matcher(normalized).find()) {
            return refusal();
        }
        Pattern extra = compile(parseExtraTerms(extraTerms));
        return extra != null && extra.matcher(normalized).find() ? refusal() : null;
    }

    private static String refusal() {
        return "This sandbox only generates business imagery - product shots, logos, illustrations and scenes. "
            + "It won't create images of real people, or anything sexual, violent or hateful. "
            + "Try describing a product, a place or a design instead.";
    }

    /**
     * Lower-cases, strips accents (so "nüde" and "nude" are the same
     * string to the matcher) and collapses the separator characters that are
     * the obvious first evasion - {@code n.u.d.e}, {@code n_u_d_e} - down to
     * nothing between letters. Not claimed as robust; see the class javadoc.
     */
    private static String normalize(String prompt) {
        if (prompt == null) {
            return "";
        }
        String folded = Normalizer.normalize(prompt, Normalizer.Form.NFKD)
            .replaceAll("\\p{M}+", "")
            .toLowerCase(Locale.ROOT);
        // Collapse single separators BETWEEN letters only, so ordinary
        // punctuation between words ("a logo, in blue") is untouched.
        return folded.replaceAll("(?<=\\p{L})[._*\\-]+(?=\\p{L})", "");
    }

    private static Set<String> parseExtraTerms(String csv) {
        Set<String> terms = new LinkedHashSet<>();
        if (csv == null || csv.isBlank()) {
            return terms;
        }
        for (String raw : csv.split(",")) {
            String term = normalize(raw).trim();
            if (!term.isEmpty()) {
                terms.add(term);
            }
        }
        return terms;
    }

    private static Pattern compile(Set<String> terms) {
        if (terms == null || terms.isEmpty()) {
            return null;
        }
        StringBuilder sb = new StringBuilder("\\b(?:");
        boolean first = true;
        for (String term : terms) {
            if (!first) {
                sb.append('|');
            }
            // Pattern.quote so an operator-supplied term containing regex
            // metacharacters is matched literally instead of blowing up the
            // whole alternation (or worse, silently matching everything).
            sb.append(Pattern.quote(term));
            first = false;
        }
        sb.append(")\\b");
        return Pattern.compile(sb.toString(), Pattern.CASE_INSENSITIVE);
    }
}
