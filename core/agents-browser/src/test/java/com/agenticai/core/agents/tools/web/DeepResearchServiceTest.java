package com.agenticai.core.agents.tools.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Effort scaling, source dedupe, span passages, the search and fetch ceilings, seed cap, and
 * verification.
 */
class DeepResearchServiceTest {

    private static final ObjectMapper MAPPER = com.agenticai.core.json.JsonMappers.plain();
    private static final String PAGE =
            "Intro text here. Solid-state batteries reach 400 Wh/kg in lab cells. Other sentence follows.";

    private static ResearchBrief brief(String effort, String... subs) {
        return new ResearchBrief("battery progress", List.of(subs), effort, null, List.of());
    }

    private static DeepResearchService service(AtomicInteger fetches) {
        return new DeepResearchService(
                DeepResearchConfig.defaults(),
                (query, count) ->
                        List.of(
                                new BraveWebSearchClient.SearchResult(
                                        "A", "https://www.example.com/a/?utm_source=x", "d"),
                                new BraveWebSearchClient.SearchResult(
                                        "B", "https://example.com/b", "d")),
                (url, maxChars) -> {
                    fetches.incrementAndGet();
                    return PAGE;
                });
    }

    @Test
    void effortScalesWorkers() {
        DeepResearchService s = service(new AtomicInteger());
        assertEquals(1, s.plan(brief("simple", "a", "b", "c"), 99).workers());
        assertEquals(1, s.plan(brief("auto"), 99).workers());
        assertEquals("comparison", s.plan(brief("auto", "a", "b"), 99).effort());
        assertEquals(3, s.plan(brief("comparison", "a", "b", "c"), 99).workers());
        assertEquals(4, s.plan(brief("comparison", "a", "b", "c", "d", "e"), 99).workers());
        assertEquals("complex", s.plan(brief("auto", "a", "b", "c", "d", "e"), 99).effort());
        assertEquals(
                6, s.plan(brief("complex", "a", "b", "c", "d", "e", "f", "g", "h"), 99).workers());
        assertEquals(2, s.plan(brief("complex", "a", "b", "c", "d", "e"), 2).workers());
        assertEquals(0, s.plan(brief("complex", "a", "b"), 0).workers());
    }

    @Test
    void dedupesSourcesAcrossWorkersAndExtractsSpans() throws Exception {
        AtomicInteger fetches = new AtomicInteger();
        DeepResearchSession session = new DeepResearchSession(12);
        Map<String, Object> out =
                service(fetches)
                        .run(
                                brief("comparison", "solid state battery", "lithium battery"),
                                session,
                                99);
        JsonNode json = MAPPER.readTree(MAPPER.writeValueAsString(out));
        assertEquals(2, json.get("sources").size());
        assertEquals(2, fetches.get());
        assertEquals(2, json.get("duplicatesRemoved").asInt());
        JsonNode passage = json.get("sources").get(0).get("passages").get(0);
        int start = passage.get("startChar").asInt();
        int end = passage.get("endChar").asInt();
        assertEquals(PAGE.substring(start, end), passage.get("quote").asString());
        assertTrue(passage.get("quote").asString().contains("400 Wh/kg"));
    }

    @Test
    void secondCallReusesCachedPagesAndFetchCeilingHolds() {
        AtomicInteger fetches = new AtomicInteger();
        DeepResearchSession session = new DeepResearchSession(1);
        DeepResearchService s = service(fetches);
        s.run(brief("simple"), session, 99);
        assertEquals(1, fetches.get());
        assertEquals(0, session.fetchesRemaining());
        s.run(brief("simple"), session, 99);
        assertEquals(1, fetches.get());
    }

    @Test
    void progressNotesArriveInPhaseOrderAndEndWithTheUsageLine() {
        java.util.List<String> notes = new java.util.ArrayList<>();
        java.util.List<Integer> percents = new java.util.ArrayList<>();
        DeepResearchService s =
                new DeepResearchService(
                        DeepResearchConfig.defaults(),
                        (query, count) ->
                                List.of(
                                        new BraveWebSearchClient.SearchResult(
                                                "A", "https://example.com/a", "d"),
                                        new BraveWebSearchClient.SearchResult(
                                                "B", "https://example.com/b", "d")),
                        (url, maxChars) -> {
                            if (url.endsWith("/b")) {
                                throw new WebSearchException("boom");
                            }
                            return PAGE;
                        },
                        (note, percent) -> {
                            notes.add(note);
                            percents.add(percent);
                        });
        DeepResearchSession session = new DeepResearchSession(12, 12);
        Map<String, Object> out =
                s.run(brief("comparison", "solid state battery", "lithium battery"), session, 99);
        assertEquals(
                List.of(
                        "Searching 2 angles",
                        "Found 2 candidate pages, reading 2",
                        "Read 1 of 2 pages (1 failed)",
                        "Extracting quotes",
                        "Used 2/12 searches, 2/12 page reads"),
                notes);
        assertTrue(
                percents.stream().allMatch(java.util.Objects::isNull),
                "the tool never claims a run percentage");
        assertEquals(2, out.get("searchesUsed"));
        assertEquals(12, out.get("searchLimit"));
        assertEquals(2, out.get("fetchesUsed"));
        assertEquals(12, out.get("fetchLimit"));

        // Second call in the same turn: the page that was read is reused, the counters keep
        // accumulating.
        notes.clear();
        Map<String, Object> second = s.run(brief("simple"), session, 99);
        assertEquals("Searching 1 angle", notes.get(0));
        assertEquals("Found 2 candidate pages, reading 1 (1 already read this turn)", notes.get(1));
        assertEquals("Used 3/12 searches, 3/12 page reads", notes.get(notes.size() - 1));
        assertEquals(3, second.get("searchesUsed"));
        assertEquals(3, second.get("fetchesUsed"));
    }

    @Test
    void exhaustedSearchCeilingStillReportsUsageAndAThrowingSinkNeverBreaksTheCall() {
        java.util.List<String> notes = new java.util.ArrayList<>();
        DeepResearchSession session = new DeepResearchSession(5, 1);
        DeepResearchService s =
                new DeepResearchService(
                        DeepResearchConfig.defaults(),
                        (q, c) ->
                                List.of(
                                        new BraveWebSearchClient.SearchResult(
                                                "A", "https://example.com/a", "d")),
                        (u, m) -> PAGE,
                        (note, percent) -> notes.add(note));
        s.run(brief("simple"), session, 99);
        notes.clear();
        Map<String, Object> refused = s.run(brief("simple"), session, 99);
        assertNotNull(refused.get("error"));
        assertEquals(List.of("Used 1/1 searches, 1/5 page reads"), notes);
        assertEquals(1, refused.get("searchesUsed"));

        DeepResearchService throwing =
                new DeepResearchService(
                        DeepResearchConfig.defaults(),
                        (q, c) ->
                                List.of(
                                        new BraveWebSearchClient.SearchResult(
                                                "A", "https://example.com/a", "d")),
                        (u, m) -> PAGE,
                        (note, percent) -> {
                            throw new IllegalStateException("progress store down");
                        });
        Map<String, Object> out = throwing.run(brief("simple"), new DeepResearchSession(5), 99);
        assertNotNull(out.get("sources"));
        // The 3-arg constructor (no sink) keeps working.
        assertNotNull(
                service(new AtomicInteger())
                        .run(brief("simple"), new DeepResearchSession(5), 99)
                        .get("sources"));
    }

    @Test
    void workerFailuresAreReportedNotThrown() {
        DeepResearchService s =
                new DeepResearchService(
                        DeepResearchConfig.defaults(),
                        (q, c) -> {
                            throw new WebSearchException("rate limited");
                        },
                        (u, m) -> PAGE);
        Map<String, Object> out = s.run(brief("simple"), new DeepResearchSession(5), 99);
        assertNotNull(out.get("failures"));
    }

    @Test
    void searchCeilingIsPerTurnAcrossCalls() {
        AtomicInteger searches = new AtomicInteger();
        DeepResearchService s =
                new DeepResearchService(
                        DeepResearchConfig.defaults(),
                        (q, c) -> {
                            searches.incrementAndGet();
                            return List.of(
                                    new BraveWebSearchClient.SearchResult(
                                            "A", "https://example.com/a", "d"));
                        },
                        (u, m) -> PAGE);
        DeepResearchSession session = new DeepResearchSession(50, 3);
        // complex with 5 sub-questions wants 5 workers; only 3 searches are left this turn.
        Map<String, Object> first = s.run(brief("complex", "a", "b", "c", "d", "e"), session, 99);
        assertEquals(3, searches.get());
        assertEquals(3, first.get("workers"));
        assertEquals(0, session.searchesRemaining());
        Map<String, Object> second = s.run(brief("simple"), session, 99);
        assertEquals(3, searches.get(), "no further paid searches once the turn ceiling is spent");
        assertNotNull(second.get("error"));
    }

    @Test
    void failedFetchesCountAgainstTheFetchCeiling() {
        AtomicInteger attempts = new AtomicInteger();
        DeepResearchService s =
                new DeepResearchService(
                        DeepResearchConfig.defaults(),
                        (q, c) ->
                                List.of(
                                        new BraveWebSearchClient.SearchResult(
                                                "A", "https://example.com/a", "d"),
                                        new BraveWebSearchClient.SearchResult(
                                                "B", "https://example.com/b", "d")),
                        (u, m) -> {
                            attempts.incrementAndGet();
                            throw new WebSearchException("boom");
                        });
        DeepResearchSession session = new DeepResearchSession(2, 50);
        s.run(brief("simple"), session, 99);
        assertEquals(2, attempts.get());
        assertEquals(0, session.fetchesRemaining());
        s.run(brief("simple"), session, 99);
        assertEquals(2, attempts.get(), "failed URLs must not be retried past the ceiling");
    }

    @Test
    void seedUrlsAreCappedSoSearchResultsStillGetRead() throws Exception {
        AtomicInteger fetches = new AtomicInteger();
        ResearchBrief withSeeds =
                new ResearchBrief(
                        "q",
                        List.of(),
                        "simple",
                        null,
                        List.of(
                                "https://s.example/1",
                                "https://s.example/2",
                                "https://s.example/3",
                                "https://s.example/4",
                                "https://s.example/5"));
        Map<String, Object> out = service(fetches).run(withSeeds, new DeepResearchSession(12), 99);
        JsonNode json = MAPPER.readTree(MAPPER.writeValueAsString(out));
        long seedSources = 0;
        for (JsonNode src : json.get("sources")) {
            if (src.get("url").asString().startsWith("https://s.example/")) {
                seedSources++;
            }
        }
        assertEquals(DeepResearchService.MAX_SEEDS, seedSources);
        assertEquals(DeepResearchService.MAX_SEEDS + 2, json.get("sources").size());
    }

    @Test
    void toolReportsInvalidBriefWithoutThrowing() {
        DeepResearchToolCallback tool =
                new DeepResearchToolCallback(
                        service(new AtomicInteger()), new DeepResearchSession(5));
        assertTrue(tool.call("{\"effort\":\"simple\"}").contains("question is required"));
        assertTrue(tool.call("not json").contains("error"));
        assertThrows(
                ResearchBrief.InvalidBriefException.class,
                () -> ResearchBrief.parse(Map.of("question", "q", "effort", "huge")));
    }

    @Test
    void verifyCitationsChecksQuotesAgainstStoredText() throws Exception {
        DeepResearchSession session = new DeepResearchSession(5);
        DeepResearchSession.Source src = session.add("https://example.com/a", "A", PAGE);
        VerifyCitationsToolCallback verify = new VerifyCitationsToolCallback(session);
        String out =
                verify.call(
                        "{\"citations\":["
                                + "{\"sourceId\":\""
                                + src.id()
                                + "\",\"quote\":\"solid-state  BATTERIES reach 400 wh/kg in lab cells.\","
                                + "\"claim\":\"Solid-state batteries reach 400 Wh/kg in lab cells.\"},"
                                + "{\"sourceId\":\""
                                + src.id()
                                + "\",\"quote\":\"never said this anywhere in the page\",\"claim\":\"x y z\"},"
                                + "{\"sourceId\":\"S99\",\"quote\":\"x\",\"claim\":\"y\"}]}");
        JsonNode json = MAPPER.readTree(out);
        assertEquals(1, json.get("verified").asInt());
        assertEquals(2, json.get("failed").asInt());
        assertTrue(json.get("results").get(0).get("verified").asBoolean());
        assertEquals("SUPPORTED", json.get("results").get(0).get("verdict").asString());
        assertFalse(json.get("results").get(1).get("verified").asBoolean());
        assertEquals("QUOTE_NOT_FOUND", json.get("results").get(1).get("verdict").asString());
        assertEquals("UNKNOWN_SOURCE", json.get("results").get(2).get("verdict").asString());
    }

    private static String verdictOf(DeepResearchSession session, String quote, String claim)
            throws Exception {
        String out =
                new VerifyCitationsToolCallback(session)
                        .call(
                                "{\"citations\":[{\"sourceId\":\"S1\","
                                        + "\"quote\":"
                                        + MAPPER.writeValueAsString(quote)
                                        + ",\"claim\":"
                                        + MAPPER.writeValueAsString(claim)
                                        + "}]}");
        return MAPPER.readTree(out).get("results").get(0).get("verdict").asString();
    }

    @Test
    void verifyFlagsAClaimInADifferentScriptThanItsQuoteInsteadOfCallingItUnsupported()
            throws Exception {
        DeepResearchSession session = new DeepResearchSession(5);
        session.add("https://example.com/a", "A", PAGE);
        assertEquals(
                "CROSS_LANGUAGE_UNVERIFIED",
                verdictOf(
                        session,
                        "Solid-state batteries reach 400 Wh/kg in lab cells.",
                        "सॉलिड-स्टेट बैटरी लैब में 400 Wh/kg तक पहुंचती हैं"));
    }

    @Test
    void verifyRequiresALongEnoughQuoteAndAClaimThatTheQuoteSupports() throws Exception {
        DeepResearchSession session = new DeepResearchSession(5);
        session.add("https://example.com/a", "A", PAGE);
        // A real but tiny fragment must not verify anything.
        assertEquals("QUOTE_TOO_SHORT", verdictOf(session, "Intro text", "Intro text here"));
        assertEquals(
                "QUOTE_TOO_SHORT",
                verdictOf(session, "400 Wh/kg", "Solid-state batteries reach 400 Wh/kg"));
        // The quote exists, but the claim is about something else.
        assertEquals(
                "NOT_SUPPORTED",
                verdictOf(
                        session,
                        "Solid-state batteries reach 400 Wh/kg in lab cells.",
                        "Lithium sulfur chargers cost twenty dollars"));
        // A claim carrying a figure the quote lacks is never supported.
        assertEquals(
                "NOT_SUPPORTED",
                verdictOf(
                        session,
                        "Solid-state batteries reach 400 Wh/kg in lab cells.",
                        "Solid-state batteries reach 900 Wh/kg in lab cells."));
        assertEquals(
                "CLAIM_MISSING",
                verdictOf(session, "Solid-state batteries reach 400 Wh/kg in lab cells.", ""));
        assertEquals(
                "SUPPORTED",
                verdictOf(
                        session,
                        "Solid-state batteries reach 400 Wh/kg in lab cells.",
                        "Lab cells of solid-state batteries reach 400 Wh/kg."));
    }

    @Test
    void verifyFailsClosedWhenThisTurnHasNoSources() throws Exception {
        String out =
                new VerifyCitationsToolCallback(new DeepResearchSession(5))
                        .call(
                                "{\"citations\":[{\"sourceId\":\"S1\",\"quote\":\"Solid-state batteries reach 400 Wh/kg\",\"claim\":\"c\"}]}");
        assertTrue(
                MAPPER.readTree(out)
                        .get("error")
                        .asString()
                        .contains("no deep_research results exist in this turn"));
    }

    @Test
    void overallDeadlineReturnsPartialResultsWithAMarkerAndDoesNotWaitForStuckWorkers()
            throws Exception {
        DeepResearchConfig cfg =
                new DeepResearchConfig(true, 6, 8, 12000, 12, 2, 6, 45, 12, 1, 20, true);
        DeepResearchService s =
                new DeepResearchService(
                        cfg,
                        (q, c) -> {
                            try {
                                Thread.sleep(30_000);
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                            }
                            return List.of();
                        },
                        (u, m) -> PAGE);
        long start = System.nanoTime();
        Map<String, Object> out = s.run(brief("simple"), new DeepResearchSession(5), 99);
        long seconds = (System.nanoTime() - start) / 1_000_000_000L;
        assertTrue(
                seconds < 10,
                "must return near the 1s deadline, not wait for the 30s worker (took "
                        + seconds
                        + "s)");
        assertEquals(Boolean.TRUE, out.get("partial"));
        assertTrue(out.get("partialMarker").toString().startsWith("PARTIAL RESULT"));
    }

    @Test
    void pageBoilerplateIsStrippedBeforeEvidenceIsStored() throws Exception {
        String page =
                "Home | Products | Pricing | Contact\nWe use cookies to improve your experience.\n"
                        + "Solid-state batteries reach 400 Wh/kg in lab cells.\nAll rights reserved.";
        DeepResearchService s =
                new DeepResearchService(
                        DeepResearchConfig.defaults(),
                        (q, c) ->
                                List.of(
                                        new BraveWebSearchClient.SearchResult(
                                                "A", "https://example.com/a", "d")),
                        (u, m) -> page);
        DeepResearchSession session = new DeepResearchSession(5);
        s.run(brief("simple"), session, 99);
        assertEquals(
                "Solid-state batteries reach 400 Wh/kg in lab cells.", session.get("S1").text());
    }

    @Test
    void researchIntentIsMultilingualAndNeedsMoreThanOneWeakWord() {
        assertTrue(ResearchIntent.looksLikeResearch("Please research EV battery makers"));
        assertTrue(ResearchIntent.looksLikeResearch("iPhone vs Pixel"));
        assertTrue(ResearchIntent.looksLikeResearch("इस विषय पर शोध करो"));
        assertTrue(ResearchIntent.looksLikeResearch("请比较两种电池"));
        assertTrue(ResearchIntent.looksLikeResearch("give me the latest report with sources"));
        assertFalse(ResearchIntent.looksLikeResearch("send me the report"));
        assertFalse(ResearchIntent.looksLikeResearch("hello there"));
        assertFalse(ResearchIntent.looksLikeResearch(null));
        assertFalse(ResearchIntent.looksLikeResearch("   "));
    }

    @Test
    void urlNormalizationDropsTrackingAndWww() {
        assertEquals(
                DeepResearchSession.normalizeUrl("http://www.Example.com/a/#frag"),
                DeepResearchSession.normalizeUrl("https://example.com/a?utm_source=z"));
    }
}
