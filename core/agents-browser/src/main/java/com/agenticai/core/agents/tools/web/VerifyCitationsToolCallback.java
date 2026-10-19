package com.agenticai.core.agents.tools.web;

import com.agenticai.core.json.JsonMappers;
import com.agenticai.core.knowledge.CitationSpans;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

/**
 * The {@code verify_citations} built-in: the verification pass of deep research. For each
 * {sourceId, quote, claim} it checks (1) the quote is long enough to mean something, (2) it occurs
 * in the stored text of the cited source (returning the exact character span), and (3) the quote
 * lexically supports the claim text ({@link CitationSupport}). Verdicts: SUPPORTED, WEAK_SUPPORT,
 * NOT_SUPPORTED, QUOTE_NOT_FOUND, QUOTE_TOO_SHORT, UNKNOWN_SOURCE, CLAIM_MISSING, CLAIM_TOO_SHORT,
 * CROSS_LANGUAGE_UNVERIFIED (claim and quote in different scripts; lexical matching cannot compare
 * them); only SUPPORTED counts as verified. Pure string matching against the turn's {@link
 * DeepResearchSession}; no network and no model call, and it is lexical, not entailment.
 */
public class VerifyCitationsToolCallback implements ToolCallback {

    public static final String TOOL_NAME = "verify_citations";
    static final int MAX_CLAIMS = 40;

    /** Fewest distinct content terms a quote must carry; shorter quotes match almost any claim. */
    static final int MIN_QUOTE_TERMS = 3;

    /**
     * Returned when this resolution holds no sources (replayed approval, edge session, a different
     * turn).
     */
    static final String NO_EVIDENCE =
            "{\"error\": \"no deep_research results exist in this turn, so nothing can be verified (this happens when "
                    + "verify_citations runs in a different turn, a replayed approval, an edge session or a separate workflow "
                    + "step than the deep_research call). Run deep_research again in this turn, then verify. Do not present "
                    + "unverified quotes as verified.\"}";

    private static final Logger log = LoggerFactory.getLogger(VerifyCitationsToolCallback.class);
    private static final ObjectMapper MAPPER = JsonMappers.plain();

    private final DeepResearchSession session;
    private final DeepResearchConfig config;

    public VerifyCitationsToolCallback(DeepResearchSession session) {
        this(session, DeepResearchConfig.defaults());
    }

    public VerifyCitationsToolCallback(DeepResearchSession session, DeepResearchConfig config) {
        this.session = session;
        this.config = config == null ? DeepResearchConfig.defaults() : config;
    }

    @Override
    public ToolDefinition getToolDefinition() {
        return ToolDefinition.builder()
                .name(TOOL_NAME)
                .description(
                        "Verifies the citations in a research report draft: for each {sourceId, quote, claim} it checks the "
                                + "quote really appears in that source's text (from deep_research) and that the quote supports "
                                + "the claim sentence you wrote. Only verdict SUPPORTED passes. Run it before presenting a cited "
                                + "report; remove or reword every claim that does not pass. Limits: the check is lexical word/number "
                                + "overlap, not entailment, and a claim that merely repeats the quote passes; a claim in a different "
                                + "script/language than its quote (e.g. a Hindi claim on an English page) cannot be checked and "
                                + "returns CROSS_LANGUAGE_UNVERIFIED - say such claims are not machine-verified.")
                .inputSchema(
                        """
                {
                    "type": "object",
                    "properties": {
                        "citations": {
                            "type": "array",
                            "description": "The citations to check.",
                            "items": {
                                "type": "object",
                                "properties": {
                                    "sourceId": {"type": "string", "description": "Source id such as S1."},
                                    "quote": {"type": "string", "description": "The quoted text the claim rests on."},
                                    "claim": {"type": "string", "description": "The claim sentence from your report that this quote backs."}
                                },
                                "required": ["sourceId", "quote", "claim"]
                            },
                            "maxItems": 40
                        }
                    },
                    "required": ["citations"]
                }
                """)
                .build();
    }

    @Override
    @SuppressWarnings("unchecked")
    public String call(String toolInput) {
        try {
            Map<String, Object> args = MAPPER.readValue(toolInput, Map.class);
            if (!(args.get("citations") instanceof List<?> list) || list.isEmpty()) {
                return "{\"error\": \"citations is required - a non-empty array of {sourceId, quote, claim}\"}";
            }
            if (session.sourceCount() == 0) {
                return NO_EVIDENCE;
            }
            List<Map<String, Object>> results = new ArrayList<>();
            int verified = 0;
            int checked = 0;
            for (Object o : list) {
                if (checked >= MAX_CLAIMS) {
                    break;
                }
                checked++;
                Map<String, Object> entry = new LinkedHashMap<>();
                Map<String, Object> c =
                        o instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
                Object id = c.get("sourceId");
                String quote = c.get("quote") == null ? "" : c.get("quote").toString().strip();
                String claim = c.get("claim") == null ? "" : c.get("claim").toString().strip();
                entry.put("sourceId", id == null ? "" : id.toString());
                if (verify(entry, session.get(id == null ? null : id.toString()), quote, claim)) {
                    verified++;
                }
                results.add(entry);
            }
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("verified", verified);
            body.put("failed", results.size() - verified);
            body.put("results", results);
            return MAPPER.writeValueAsString(body);
        } catch (Exception e) {
            log.warn("verify_citations failed unexpectedly", e);
            return "{\"error\": \"could not parse citations - expected {\\\"citations\\\":[{\\\"sourceId\\\":\\\"S1\\\",\\\"quote\\\":\\\"...\\\",\\\"claim\\\":\\\"...\\\"}]}\"}";
        }
    }

    /**
     * Fills {@code entry} with verdict/reason (and span when found); returns true only for
     * SUPPORTED.
     */
    private boolean verify(
            Map<String, Object> entry,
            DeepResearchSession.Source source,
            String quote,
            String claim) {
        if (source == null) {
            return fail(
                    entry,
                    "UNKNOWN_SOURCE",
                    "unknown source id - cite only ids returned by deep_research");
        }
        if (quote.isEmpty()) {
            return fail(entry, "QUOTE_TOO_SHORT", "quote is empty");
        }
        if (!longEnough(quote)) {
            return fail(
                    entry,
                    "QUOTE_TOO_SHORT",
                    "quote is too short to verify anything (minimum "
                            + config.minQuoteChars()
                            + " characters and "
                            + MIN_QUOTE_TERMS
                            + " distinct content terms) - "
                            + "quote the full sentence the claim rests on");
        }
        if (claim.isEmpty()) {
            return fail(
                    entry,
                    "CLAIM_MISSING",
                    "claim is required - pass the report sentence this quote backs");
        }
        CitationSpans.Span span = CitationSpans.locate(source.text(), quote);
        if (span == null) {
            return fail(
                    entry,
                    "QUOTE_NOT_FOUND",
                    "quote not found in the source text - do not present it as a quotation");
        }
        entry.put("startChar", span.start());
        entry.put("endChar", span.end());
        UnicodeScriptClass claimScript = dominantScript(claim);
        UnicodeScriptClass quoteScript = dominantScript(quote);
        if (claimScript != UnicodeScriptClass.OTHER
                && quoteScript != UnicodeScriptClass.OTHER
                && claimScript != quoteScript) {
            // Lexical matching cannot compare across scripts (a Hindi claim vs an English quote has
            // ~zero
            // overlap), so say so instead of reporting a misleading NOT_SUPPORTED.
            return fail(
                    entry,
                    "CROSS_LANGUAGE_UNVERIFIED",
                    "the claim ("
                            + claimScript
                            + ") and the quote ("
                            + quoteScript
                            + ") are in different scripts and this check is lexical, so it cannot confirm "
                            + "support. The quote was found in the source; treat the claim as NOT machine-verified and say "
                            + "so, or restate the claim in the quote's language.");
        }
        CitationSupport.Result support = CitationSupport.assess(claim, quote);
        entry.put("coverage", support.coverage());
        CitationSupport.Verdict verdict = support.verdict();
        if (verdict == CitationSupport.Verdict.SUPPORTED) {
            entry.put("verified", true);
            entry.put("verdict", "SUPPORTED");
            return true;
        }
        if (verdict == CitationSupport.Verdict.WEAK_SUPPORT) {
            return fail(
                    entry,
                    "WEAK_SUPPORT",
                    support.negationMismatch()
                            ? "the quote exists but only one side of the claim is negated - check the polarity and reword"
                            : "the quote exists but covers only part of the claim - narrow the claim to what the quote says");
        }
        if (verdict == CitationSupport.Verdict.CLAIM_TOO_SHORT) {
            return fail(
                    entry,
                    "CLAIM_TOO_SHORT",
                    "the claim is too short to compare - state the full claim sentence");
        }
        String why =
                support.missingNumbers().isEmpty()
                        ? "the quote exists in the source but does not support this claim - it is about something else"
                        : "the claim states "
                                + support.missingNumbers()
                                + " which the quote does not contain";
        return fail(entry, "NOT_SUPPORTED", why);
    }

    private boolean longEnough(String quote) {
        int chars = quote.codePointCount(0, quote.length());
        boolean cjk =
                quote.codePoints()
                        .anyMatch(
                                cp -> {
                                    Character.UnicodeScript sc = Character.UnicodeScript.of(cp);
                                    return sc == Character.UnicodeScript.HAN
                                            || sc == Character.UnicodeScript.HIRAGANA
                                            || sc == Character.UnicodeScript.KATAKANA
                                            || sc == Character.UnicodeScript.HANGUL;
                                });
        // CJK packs more meaning per character, so the character floor is halved there.
        int floor = cjk ? Math.max(6, config.minQuoteChars() / 2) : config.minQuoteChars();
        return chars >= floor && CitationSpans.termSet(quote).size() >= MIN_QUOTE_TERMS;
    }

    private enum UnicodeScriptClass {
        LATIN,
        CJK,
        DEVANAGARI,
        ARABIC,
        CYRILLIC,
        OTHER
    }

    /**
     * The script most of the text's letters belong to; OTHER when there are no letters or no clear
     * majority.
     */
    private static UnicodeScriptClass dominantScript(String text) {
        int[] counts = new int[UnicodeScriptClass.values().length];
        int letters = 0;
        for (int cp : text.codePoints().toArray()) {
            if (!Character.isLetter(cp)) {
                continue;
            }
            letters++;
            Character.UnicodeScript sc = Character.UnicodeScript.of(cp);
            UnicodeScriptClass k =
                    switch (sc) {
                        case LATIN -> UnicodeScriptClass.LATIN;
                        case HAN, HIRAGANA, KATAKANA, HANGUL -> UnicodeScriptClass.CJK;
                        case DEVANAGARI -> UnicodeScriptClass.DEVANAGARI;
                        case ARABIC -> UnicodeScriptClass.ARABIC;
                        case CYRILLIC -> UnicodeScriptClass.CYRILLIC;
                        default -> UnicodeScriptClass.OTHER;
                    };
            counts[k.ordinal()]++;
        }
        if (letters == 0) {
            return UnicodeScriptClass.OTHER;
        }
        for (UnicodeScriptClass k : UnicodeScriptClass.values()) {
            if (k != UnicodeScriptClass.OTHER && counts[k.ordinal()] * 2 > letters) {
                return k;
            }
        }
        return UnicodeScriptClass.OTHER;
    }

    private static boolean fail(Map<String, Object> entry, String verdict, String reason) {
        entry.put("verified", false);
        entry.put("verdict", verdict);
        entry.put("reason", reason);
        return false;
    }
}
