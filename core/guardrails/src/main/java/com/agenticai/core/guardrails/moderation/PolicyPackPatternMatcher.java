package com.agenticai.core.guardrails.moderation;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * Phase P6, Layer 1 (deterministic). The pattern-rule half of a policy pack - the same cheap,
 * high-confidence regex/keyword technique {@code PatternBasedGuardrailService} uses for tool-output
 * injection, applied here to content-moderation categories instead.
 *
 * <p>These rule sets are intentionally MODEST and illustrative, not exhaustive - exactly the honest
 * posture {@code PatternBasedGuardrailService} takes about its own regexes. A regex layer catches
 * blatant, unambiguous cases cheaply and deterministically; the {@link
 * PolicyPackSemanticClassifier} layer above it is what catches
 * paraphrased/obfuscated/context-dependent cases a fixed regex structurally cannot. A production
 * deployment is expected to extend these lists (or, better, drive them from admin-editable config)
 * - the seams are here; the word lists are a starting point, deliberately kept small and
 * non-slur-bearing in source.
 *
 * <p>A pattern hit is treated as full ({@code 100}) confidence by {@link ModerationService} - a
 * deterministic rule either matched or it didn't.
 */
@Component
public class PolicyPackPatternMatcher {

    /**
     * Per-pack compiled patterns. FOUL_LANGUAGE/HARASSMENT/EXTREMISM keep their literal term lists
     * deliberately mild in source (this file is committed, reviewed, and read by people); the real
     * value of the pack is the seam + the semantic layer, not an exhaustive slur dictionary baked
     * into version control. PII is where a regex layer genuinely shines (structured formats), so it
     * carries the most substantive rules.
     *
     * <p><b>Invariant:</b> a pack whose {@link ModerationPolicyPack#enforcement()} is {@code
     * CLASSIFIER} (today: ILLEGAL, CSAM) must NEVER gain an entry here - authoring keyword lists
     * for those categories is exactly what the catalogue's design refuses to do (trivially
     * bypassed, fires hardest on journalism/research/ counter-speech, and puts this repo's authors
     * in the business of writing the list). Their absence is load-bearing, not an omission: {@link
     * #firstMatch} returns empty for them via {@code getOrDefault}, making the pattern layer a
     * genuine no-op. Pinned by {@code ModerationPolicyPackCatalogueTest}. (Housekeeping note:
     * {@code Map.of} tops out at 10 pairs - if the catalogue ever grows past 10 pattern-backed
     * packs, switch to {@code Map.ofEntries}.)
     */
    private static final Map<ModerationPolicyPack, List<NamedPattern>> RULES =
            Map.of(
                    ModerationPolicyPack.FOUL_LANGUAGE,
                            List.of(
                                    new NamedPattern(
                                            "profanity",
                                            Pattern.compile(
                                                    "\\b(f[u\\*]ck|sh[i\\*]t|b[i\\*]tch|a[s\\*]{2}hole)\\b",
                                                    Pattern.CASE_INSENSITIVE))),
                    ModerationPolicyPack.RESTRICTED_CONTENT,
                            List.of(
                                    new NamedPattern(
                                            "explicit-adult",
                                            Pattern.compile(
                                                    "\\b(porn|xxx|nsfw)\\b",
                                                    Pattern.CASE_INSENSITIVE)),
                                    new NamedPattern(
                                            "gambling",
                                            Pattern.compile(
                                                    "\\b(casino|betting\\s+odds|place\\s+a\\s+bet)\\b",
                                                    Pattern.CASE_INSENSITIVE))),
                    // Ordered most-specific-first: the structured, tightly-shaped
                    // formats (SSN/PAN/Aadhaar/card) are checked BEFORE the deliberately
                    // loose phone rule, so e.g. an SSN "123-45-6789" is labelled "ssn"
                    // rather than being swept up by the broad phone matcher. firstMatch
                    // returns the first rule that fires, so order is the disambiguator.
                    ModerationPolicyPack.PII,
                            List.of(
                                    new NamedPattern(
                                            "email",
                                            Pattern.compile(
                                                    "[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}")),
                                    // US SSN shape.
                                    new NamedPattern(
                                            "ssn",
                                            Pattern.compile("(?<!\\d)\\d{3}-\\d{2}-\\d{4}(?!\\d)")),
                                    // Indian PAN (this platform is India-first, see project docs).
                                    new NamedPattern(
                                            "pan", Pattern.compile("\\b[A-Z]{5}\\d{4}[A-Z]\\b")),
                                    // Indian Aadhaar shape (12 digits, commonly 4-4-4 grouped).
                                    new NamedPattern(
                                            "aadhaar",
                                            Pattern.compile(
                                                    "(?<!\\d)\\d{4}\\s?\\d{4}\\s?\\d{4}(?!\\d)")),
                                    // Credit-card-shaped 13-16 digit runs (optionally space/dash
                                    // grouped).
                                    new NamedPattern(
                                            "card-number",
                                            Pattern.compile("(?<!\\d)(?:\\d[ -]?){13,16}(?!\\d)")),
                                    // Loose international-ish phone shape: 10-15 digits with
                                    // optional
                                    // separators/leading +. Kept loose on purpose, and LAST, so it
                                    // only catches numbers the more specific rules above didn't -
                                    // false positives here queue for review, not a hard block.
                                    new NamedPattern(
                                            "phone",
                                            Pattern.compile(
                                                    "(?<!\\d)(\\+?\\d[\\d\\s().-]{8,}\\d)(?!\\d)"))),
                    ModerationPolicyPack.HARASSMENT,
                            List.of(
                                    new NamedPattern(
                                            "threat",
                                            Pattern.compile(
                                                    "\\bi(?:'m| am)?\\s+going\\s+to\\s+(kill|hurt|beat)\\s+you\\b",
                                                    Pattern.CASE_INSENSITIVE)),
                                    new NamedPattern(
                                            "dehumanizing",
                                            Pattern.compile(
                                                    "\\byou(?:'re| are)?\\s+(worthless|pathetic|a\\s+loser)\\b",
                                                    Pattern.CASE_INSENSITIVE))),
                    ModerationPolicyPack.EXTREMISM,
                            List.of(
                                    // Rhetoric markers only - NOT a terrorism detector (see the
                                    // pack's javadoc).
                                    new NamedPattern(
                                            "violent-ideology",
                                            Pattern.compile(
                                                    "\\b(ethnic\\s+cleansing|master\\s+race|kill\\s+all\\s+the)\\b",
                                                    Pattern.CASE_INSENSITIVE))),
                    ModerationPolicyPack.SELF_HARM,
                            List.of(
                                    new NamedPattern(
                                            "suicidal-intent",
                                            Pattern.compile(
                                                    "\\b(kill\\s+myself|end\\s+my\\s+life|want\\s+to\\s+die)\\b",
                                                    Pattern.CASE_INSENSITIVE)),
                                    new NamedPattern(
                                            "self-injury",
                                            Pattern.compile(
                                                    "\\b(cut\\s+myself|self[\\s-]?harm)\\b",
                                                    Pattern.CASE_INSENSITIVE))));

    /**
     * @return the label of the first rule in {@code pack} that matches {@code content}, or empty if
     *     none match / the pack has no rules / content is blank.
     */
    public Optional<String> firstMatch(ModerationPolicyPack pack, String content) {
        if (content == null || content.isBlank()) {
            return Optional.empty();
        }
        for (NamedPattern rule : RULES.getOrDefault(pack, List.of())) {
            if (rule.pattern().matcher(content).find()) {
                return Optional.of(rule.label());
            }
        }
        return Optional.empty();
    }

    private record NamedPattern(String label, Pattern pattern) {}
}
