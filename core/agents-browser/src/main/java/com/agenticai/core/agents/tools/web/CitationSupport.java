package com.agenticai.core.agents.tools.web;

import com.agenticai.core.knowledge.CitationSpans;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Lexical check of whether a quote SUPPORTS the claim written next to it. Deterministic and free,
 * so it is deliberately conservative: it catches the common failure (a real quote attached to a
 * claim it does not say, or a claim with a figure the quote lacks) but it is NOT entailment and
 * cannot judge paraphrase or meaning. {@code SUPPORTED} means "the claim's content words and every
 * number appear in the quote".
 */
public final class CitationSupport {

    /** Claim terms that must appear in the quote for a SUPPORTED verdict. */
    static final double SUPPORTED_COVERAGE = 0.6;

    /**
     * Below SUPPORTED but at or above this the verdict is WEAK_SUPPORT (reported, never verified).
     */
    static final double WEAK_COVERAGE = 0.4;

    private static final Pattern NUMBER = Pattern.compile("\\p{N}+(?:[.,]\\p{N}+)*");
    private static final Pattern NEGATION =
            Pattern.compile("(?iu)\\b(?:not|no|never|none|cannot|without|neither|nor)\\b|n't\\b");

    public enum Verdict {
        SUPPORTED,
        WEAK_SUPPORT,
        NOT_SUPPORTED,
        CLAIM_TOO_SHORT
    }

    /** {@code coverage} is the share of the claim's content terms found in the quote (0..1). */
    public record Result(
            Verdict verdict,
            double coverage,
            List<String> missingNumbers,
            boolean negationMismatch) {}

    private CitationSupport() {}

    public static Result assess(String claim, String quote) {
        Set<String> claimTerms = CitationSpans.termSet(claim);
        if (claimTerms.size() < 2) {
            return new Result(Verdict.CLAIM_TOO_SHORT, 0, List.of(), false);
        }
        Set<String> quoteTerms = CitationSpans.termSet(quote);
        int hit = 0;
        for (String t : claimTerms) {
            if (quoteTerms.contains(t)) {
                hit++;
            }
        }
        double coverage = (double) hit / claimTerms.size();
        Set<String> quoteNumbers = numbers(quote);
        List<String> missing = new java.util.ArrayList<>();
        for (String n : numbers(claim)) {
            if (!quoteNumbers.contains(n)) {
                missing.add(n);
            }
        }
        boolean negationMismatch = NEGATION.matcher(claim).find() != NEGATION.matcher(quote).find();
        Verdict verdict;
        if (!missing.isEmpty() || coverage < WEAK_COVERAGE) {
            verdict = Verdict.NOT_SUPPORTED;
        } else if (coverage < SUPPORTED_COVERAGE || negationMismatch) {
            verdict = Verdict.WEAK_SUPPORT;
        } else {
            verdict = Verdict.SUPPORTED;
        }
        return new Result(verdict, Math.round(coverage * 100.0) / 100.0, missing, negationMismatch);
    }

    /** Numbers normalised so "1,000" and "1000" compare equal. */
    static Set<String> numbers(String text) {
        Set<String> out = new LinkedHashSet<>();
        if (text == null) {
            return out;
        }
        Matcher m = NUMBER.matcher(text);
        while (m.find()) {
            out.add(m.group().replace(",", "").toLowerCase(Locale.ROOT));
        }
        return new HashSet<>(out);
    }
}
