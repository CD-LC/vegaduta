package com.agenticai.core.knowledge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.agenticai.core.knowledge.dto.SearchResultResponse;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CitationSpansTest {

    private static final String TEXT =
        "Refunds are issued within 14 days. Shipping to Canada takes 5 to 7 business days.\n\nContact support for help.";

    @Test
    void bestPicksTheSentenceWithMostQueryTermsAndOffsetsMatch() {
        CitationSpans.Span span = CitationSpans.best(TEXT, "how long does shipping to Canada take");
        assertNotNull(span);
        assertEquals("Shipping to Canada takes 5 to 7 business days.", span.quote());
        assertEquals(span.quote(), TEXT.substring(span.start(), span.end()));
    }

    @Test
    void bestReturnsNullWithoutOverlapOrInput() {
        assertNull(CitationSpans.best(TEXT, "quantum chromodynamics"));
        assertNull(CitationSpans.best("", "refunds"));
        assertNull(CitationSpans.best(TEXT, " "));
    }

    @Test
    void locateIsWhitespaceAndCaseInsensitiveAndReturnsOriginalOffsets() {
        CitationSpans.Span span = CitationSpans.locate(TEXT, "shipping   to canada TAKES 5");
        assertNotNull(span);
        assertEquals("Shipping to Canada takes 5", TEXT.substring(span.start(), span.end()));
        assertNull(CitationSpans.locate(TEXT, "free shipping worldwide"));
    }

    @Test
    void spanFieldsAreAdditiveAndOldConstructorsStillWork() {
        UUID id = UUID.randomUUID();
        SearchResultResponse old = new SearchResultResponse(id, "s", 0, "text", id, 0.1, null);
        assertNull(old.quote());
        assertNull(old.startChar());
        SearchResultResponse spanned = old.withSpan(0, 4, "text").withDistance(0.5).withSourceUrl("https://x");
        assertEquals("text", spanned.quote());
        assertEquals(4, spanned.endChar());
        assertEquals("https://x", spanned.sourceUrl());
        assertEquals(0.5, spanned.distance());
    }

    @Test
    void boilerplateSentencesAreNeverChosenAndCanBeStripped() {
        String page = "Home | Products | Pricing | Contact\n"
            + "We use cookies to improve your experience.\n"
            + "Solid-state batteries reach 400 Wh/kg in lab cells.\n"
            + "About Us\n"
            + "All rights reserved.";
        CitationSpans.Span span = CitationSpans.bestForResearch(page, "solid-state batteries cookies experience pricing");
        assertNotNull(span);
        assertEquals("Solid-state batteries reach 400 Wh/kg in lab cells.", span.quote());
        assertEquals("Solid-state batteries reach 400 Wh/kg in lab cells.", CitationSpans.stripBoilerplate(page));
        assertEquals(true, CitationSpans.isBoilerplate("Home | Products | Pricing | Contact"));
        assertEquals(false, CitationSpans.isBoilerplate("Refunds are issued within 14 days."));
    }

    @Test
    void hindiSentencesSplitAtDandaAndKeepWholeWords() {
        String text = "भारत की राजधानी नई दिल्ली है। हिन्दी भाषा दुनिया में बहुत बोली जाती है। मौसम आज साफ़ रहेगा।";
        CitationSpans.Span span = CitationSpans.bestForResearch(text, "हिन्दी भाषा कितनी बोली जाती है");
        assertNotNull(span);
        assertEquals("हिन्दी भाषा दुनिया में बहुत बोली जाती है।", span.quote());
        assertEquals(span.quote(), text.substring(span.start(), span.end()));
    }

    @Test
    void cjkSentencesSplitAtFullWidthStopsAndMatchOnBigrams() {
        String text = "今天天气很好。固态电池的能量密度已经达到每公斤四百瓦时。明天可能下雨。";
        CitationSpans.Span span = CitationSpans.bestForResearch(text, "固态电池能量密度");
        assertNotNull(span);
        assertEquals("固态电池的能量密度已经达到每公斤四百瓦时。", span.quote());
        assertEquals(true, CitationSpans.termSet("固态电池").contains("固态"));
    }
}
