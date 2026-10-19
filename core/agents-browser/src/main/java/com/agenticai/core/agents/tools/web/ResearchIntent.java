package com.agenticai.core.agents.tools.web;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Decides, from the user's message alone, whether the deep_research / verify_citations schemas are
 * worth their prompt tokens this turn. No match means zero extra tokens. Deliberately keyword-based
 * and multilingual-safe (substring match, so unspaced scripts work; short Latin tokens are
 * word-bounded).
 *
 * <p>A STRONG phrase alone qualifies ("research", "compare", "literature review", "fact-check",
 * "state of the art", and their Hindi/Spanish/French/German/Chinese/Japanese/Korean/Arabic
 * equivalents). WEAK words (sources, citations, report, latest, analysis...) qualify only in pairs,
 * so "send me the report" does not switch the tools on.
 */
public final class ResearchIntent {

    private static final String[] STRONG = {
        "deep research",
        "research",
        "literature review",
        "fact-check",
        "fact check",
        "state of the art",
        "state-of-the-art",
        "market scan",
        "compare",
        "comparison",
        "pros and cons",
        "in-depth",
        "in depth",
        "investigate",
        "शोध",
        "अनुसंधान",
        "तुलना", // hi: shodh, anusandhan, tulna
        "investigar",
        "investigaci",
        "comparar",
        "comparaci",
        "recherche",
        "comparer",
        "comparaison",
        "recherchier",
        "vergleich",
        "forschung",
        "研究",
        "调研",
        "调查",
        "比较",
        "对比", // zh
        "調査",
        "比較", // ja
        "조사",
        "비교",
        "연구", // ko
        "بحث",
        "مقارنة" // ar
    };
    private static final String[] WEAK = {
        "sources",
        "citations",
        "cite",
        "references",
        "report",
        "latest",
        "analysis",
        "analyze",
        "analyse",
        "overview",
        "evidence",
        "survey",
        "trends",
        "summarize the",
        "what is the current",
        "स्रोत",
        "रिपोर्ट",
        "विश्लेषण", // hi
        "fuentes",
        "informe",
        "quellen",
        "报告",
        "分析",
        "来源",
        "レポート",
        "보고서",
        "분석"
    };
    private static final Pattern VERSUS = Pattern.compile("(?iu)\\b(?:vs\\.?|versus)\\b");

    private ResearchIntent() {}

    public static boolean looksLikeResearch(String message) {
        if (message == null || message.isBlank()) {
            return false;
        }
        String lower = message.toLowerCase(Locale.ROOT);
        for (String k : STRONG) {
            if (lower.contains(k)) {
                return true;
            }
        }
        if (VERSUS.matcher(message).find()) {
            return true;
        }
        int weak = 0;
        for (String k : WEAK) {
            if (lower.contains(k) && ++weak >= 2) {
                return true;
            }
        }
        return false;
    }
}
