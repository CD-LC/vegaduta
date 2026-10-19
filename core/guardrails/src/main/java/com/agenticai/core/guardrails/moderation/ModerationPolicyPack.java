package com.agenticai.core.guardrails.moderation;

import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Phase P6 (content moderation). An admin-configurable, opt-in category of
 * content the platform can be told to moderate for a given tenant/plan. Each
 * pack is a set of deterministic pattern rules ({@link PolicyPackPatternMatcher},
 * the same regex/keyword technique {@code PatternBasedGuardrailService} already
 * uses for tool-output injection) plus an optional semantic-classifier layer
 * ({@link PolicyPackSemanticClassifier}, the same LLM-judge technique {@code
 * SemanticGuardrailService} already uses).
 *
 * <p>Since the 2026-08 moderation unification this enum is ALSO the admin
 * catalogue behind {@code GET/PUT /api/platform/moderation/policy-packs}
 * (previously a second, private enum in {@code core/platform} that drifted from
 * this one and shared its class name - see V216's migration header for the full
 * story). Each constant therefore carries the metadata an operator surface
 * needs: a display label, an {@link Enforcement} mode that states honestly
 * whether this repo authors keyword lists for the category, a description, and
 * the {@link #requiresLegalReview()} legal gate.
 *
 * <p><b>Nothing here is on by default.</b> Every pack resolves to disabled
 * unless a platform admin explicitly enables it for a tenant, a plan, or
 * globally (see {@link ModerationPolicyService}) - this whole subsystem is
 * default-safe/opt-in per the P6 requirement. {@link #defaultEnabled()} is
 * catalogue METADATA (a "recommended" hint for the admin UI), never a
 * resolution fallback - see its javadoc.
 *
 * <p>The two highest-stakes real-world harms - CSAM and credible
 * violence/terrorism - are deliberately NOT pattern-based packs here.
 * Detecting them is the job of the pluggable {@link ExternalSafetyClassifier}
 * seam (a real vendor + legal counsel, never a regex), and their handling
 * routes to a restricted audit path. {@link #EXTREMISM} is the closest
 * pattern-pack neighbour (hate/extremist rhetoric), but it is explicitly NOT a
 * terrorism detector - see its note. {@link #CSAM} additionally appears below
 * as a legally-gated CATALOGUE entry, so an operator can see the category
 * exists and is deliberately unavailable - but it can never be enabled through
 * this codepath and never reaches the pattern or semantic layers.
 */
public enum ModerationPolicyPack {

    /** Profanity / obscene language. Lowest-stakes, highest-volume pack. */
    FOUL_LANGUAGE(
        "foul_language",
        "Foul language",
        Enforcement.DETERMINISTIC_PATTERN,
        false,
        true,
        "Deterministic profanity matching. Lowest-stakes pack and the only one (with personal data) "
            + "recommended for broad enablement."),

    /**
     * Content a tenant has chosen to restrict for its own brand/audience
     * reasons (e.g. adult content, gambling, weapons) - a deliberately
     * tenant-policy pack, not a legal one.
     */
    RESTRICTED_CONTENT(
        "restricted_content",
        "Restricted goods and services",
        Enforcement.DETERMINISTIC_PATTERN,
        false,
        false,
        "Regulated categories (adult content, gambling, weapons, and similar) where the operator's "
            + "own jurisdiction and brand decide the line - a tenant-policy pack, not a legal one."),

    /**
     * Personally identifiable information (emails, phone numbers,
     * government IDs, card-shaped numbers). Note this is CONTENT-level PII
     * scanning of agent input/output, distinct from - and complementary to -
     * the secret-shaped-string redaction {@code PatternBasedGuardrailService}
     * already does on tool output.
     */
    PII(
        "pii",
        "Personal data",
        Enforcement.DETERMINISTIC_PATTERN,
        false,
        true,
        "Personal and credential data leaving the platform. Structured formats (emails, government "
            + "IDs, card-shaped numbers) are where a deterministic layer genuinely shines."),

    /** Harassment, bullying, targeted abuse. */
    HARASSMENT(
        "harassment",
        "Harassment and targeted abuse",
        Enforcement.DETERMINISTIC_PATTERN,
        false,
        false,
        "Harassment, bullying, threats, or targeted abuse of a person."),

    /**
     * Hate speech / violent-extremist rhetoric. This is a pattern+semantic
     * content pack for rhetoric, NOT a credible-terrorism-threat detector -
     * that far higher-stakes, legally-loaded classification is the {@link
     * ExternalSafetyClassifier} seam's responsibility and routes to the
     * restricted audit path, never here.
     *
     * <p>The retired platform catalogue listed a broader classifier-only
     * "terrorism" category; it maps here as a read-side alias (see {@link
     * #fromConfigKey}) rather than a ninth constant, because two catalogue
     * entries for one operator concept would be worse than one honestly-scoped
     * entry. Enforcement stays {@link Enforcement#DETERMINISTIC_PATTERN}
     * because rhetoric-marker rules for this pack DO exist in {@link
     * PolicyPackPatternMatcher} and still fire - labelling it CLASSIFIER
     * would be false.
     */
    EXTREMISM(
        "extremism",
        "Hate speech and violent-extremist rhetoric",
        Enforcement.DETERMINISTIC_PATTERN,
        false,
        false,
        "Hate speech and violent-extremist RHETORIC. Keyword lists in this territory fire hardest "
            + "on journalism and counter-speech, so the rules stay minimal and rhetoric-scoped; a "
            + "CREDIBLE terrorism threat is the external safety classifier's job and routes to the "
            + "restricted audit path, never here."),

    /** Self-harm / suicide content. Handled with a review-first posture. */
    SELF_HARM(
        "self_harm",
        "Self-harm and suicide",
        Enforcement.DETERMINISTIC_PATTERN,
        false,
        false,
        "Content expressing or encouraging self-harm or suicide. Handled with a review-first "
            + "posture - route to a human wherever thresholds allow, rather than hard-blocking a "
            + "person who may be in crisis."),

    /**
     * Solicitation/facilitation of clearly illegal activity. Adopted from the
     * retired platform catalogue; classifier-only, so it carries NO entries in
     * {@link PolicyPackPatternMatcher} and does nothing unless its semantic
     * layer is explicitly enabled AND the call supplies a ChatClient.
     */
    ILLEGAL(
        "illegal",
        "Illegal activity",
        Enforcement.CLASSIFIER,
        false,
        false,
        "Solicitation of clearly illegal activity. Classifier-backed: intent, not keywords - "
            + "discussion, reporting and research must not trip it."),

    /**
     * Child sexual abuse material. CATALOGUE-ONLY: the constant exists so the
     * admin surface can state plainly that the category exists and is
     * deliberately unavailable. {@link ModerationPolicyService#resolveEnabled}
     * force-reads it as disabled and the write path refuses to enable it (409
     * via {@link ModerationPolicyPackNotAvailableException}), so it can never
     * enter {@code enabledPacks()} and neither the pattern nor the semantic
     * layer can ever be asked about it. The real detection machinery for this
     * category is the {@link ExternalSafetyClassifier} seam plus the
     * restricted audit trail ({@code ModerationRestrictedAuditService},
     * tenant V121), gated on legal + vendor sign-off.
     */
    CSAM(
        "csam",
        "Child sexual abuse material",
        Enforcement.CLASSIFIER,
        true,
        false,
        "Blocked pending legal review. Carries mandatory reporting obligations and requires a "
            + "restricted evidence trail; cannot be enabled from this API until that flow is "
            + "designed with counsel.");

    /**
     * How a pack is actually enforced - and therefore whether patterns belong
     * in this repo at all. Copied from the retired platform catalogue, whose
     * design point this is: shipping a hand-written list of e.g. "illegal
     * activity words" would be trivially bypassed, would fire constantly on
     * news, research and counter-speech, and would put this platform's own
     * authors in the business of authoring that list. CLASSIFIER packs
     * therefore carry NO patterns in this codebase at all - an invariant
     * pinned by {@code ModerationPolicyPackCatalogueTest}.
     */
    public enum Enforcement {
        /** Regex/keyword work, handled by {@link PolicyPackPatternMatcher}. */
        DETERMINISTIC_PATTERN,
        /** Semantic judge + external safety classifier. No patterns are authored here. */
        CLASSIFIER
    }

    /**
     * Read-side aliases: the ids the retired {@code core/platform} catalogue
     * used for categories this enum names differently. Accepted by {@link
     * #fromConfigKey} ONLY - {@link #configKey()} remains the single
     * write-side/storage token - so a V206-era row carried over by V216, or an
     * operator script written against the old {@code /api/platform} contract,
     * still resolves instead of silently vanishing.
     */
    private static final Map<String, String> LEGACY_ID_ALIASES = Map.of(
        "profanity", "foul_language",
        "restricted", "restricted_content",
        // The old catalogue's classifier-only "terrorism" category folds into
        // EXTREMISM (one operator concept, one entry) - see EXTREMISM's note.
        "terrorism", "extremism"
    );

    private final String configKey;
    private final String displayName;
    private final Enforcement enforcement;
    private final boolean requiresLegalReview;
    private final boolean defaultEnabled;
    private final String description;

    ModerationPolicyPack(String configKey, String displayName, Enforcement enforcement,
                         boolean requiresLegalReview, boolean defaultEnabled, String description) {
        this.configKey = configKey;
        this.displayName = displayName;
        this.enforcement = enforcement;
        this.requiresLegalReview = requiresLegalReview;
        this.defaultEnabled = defaultEnabled;
        this.description = description;
    }

    /**
     * The stable snake_case token used in {@code platform_setting} keys and
     * persisted on review-queue rows - intentionally decoupled from the Java
     * enum name so renaming the constant never silently orphans stored config
     * or audit history.
     */
    public String configKey() {
        return configKey;
    }

    /** Human label for the admin UI. Never used as an identifier anywhere. */
    public String displayName() {
        return displayName;
    }

    public Enforcement enforcement() {
        return enforcement;
    }

    /**
     * True when the pack cannot be enabled from any API until a legal review
     * has happened. Enforced in TWO places - {@link
     * ModerationPolicyService#resolveEnabled} force-reads the pack as
     * disabled, and the write path refuses the enable with a 409 - so do not
     * read this as advisory.
     */
    public boolean requiresLegalReview() {
        return requiresLegalReview;
    }

    /**
     * CATALOGUE METADATA ONLY - the admin UI renders this as "recommended".
     * {@link ModerationPolicyService} deliberately does NOT use it as a
     * resolution fallback: the runtime default for every pack is false, always.
     * A pack that switches itself on the moment the master {@code
     * moderation.enabled} switch flips would start firing platform-wide on the
     * live agent loop with nobody having opted in - which is how legitimate
     * traffic starts silently failing.
     */
    public boolean defaultEnabled() {
        return defaultEnabled;
    }

    /** Operator-facing description of what the category covers and how it is enforced. */
    public String description() {
        return description;
    }

    /**
     * Resolves a stored/wire token back to a pack. Accepts every {@link
     * #configKey()} plus the retired platform catalogue's ids as read-side
     * aliases ({@code profanity}, {@code restricted}, {@code terrorism}) -
     * see {@link #LEGACY_ID_ALIASES} for why those exist.
     */
    public static Optional<ModerationPolicyPack> fromConfigKey(String key) {
        if (key == null) {
            return Optional.empty();
        }
        String normalized = key.trim().toLowerCase(Locale.ROOT);
        String canonical = LEGACY_ID_ALIASES.getOrDefault(normalized, normalized);
        for (ModerationPolicyPack pack : values()) {
            if (pack.configKey.equals(canonical)) {
                return Optional.of(pack);
            }
        }
        return Optional.empty();
    }
}
