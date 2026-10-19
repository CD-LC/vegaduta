package com.agenticai.core.agents.tools.web;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Pins {@code web_search}'s failure contract, which is the whole reason this class is shaped the
 * way it is.
 *
 * <p>The single most important property: {@link WebSearchToolCallback#call} NEVER throws. A thrown
 * exception out of a ToolCallback fails the entire turn, so a search outage would take the
 * conversation down with it instead of letting the model say "search failed, let me try a URL
 * directly". Every test below that asserts a returned string is really asserting that absence of a
 * throw, and they are written against the paths most likely to regress: malformed model output, a
 * missing platform key, and an upstream failure.
 */
class WebSearchToolCallbackTest {

    /** A client that fails the way the real one fails: a pre-worded WebSearchException. */
    private static BraveWebSearchClient failingWith(String message) {
        return new BraveWebSearchClient() {
            @Override
            public List<SearchResult> search(String query, Integer count, String apiKey) {
                throw new WebSearchException(message);
            }
        };
    }

    private static BraveWebSearchClient returning(List<BraveWebSearchClient.SearchResult> results) {
        return new BraveWebSearchClient() {
            @Override
            public List<SearchResult> search(String query, Integer count, String apiKey) {
                return results;
            }
        };
    }

    private static WebSearchToolCallback callback(BraveWebSearchClient client, String key) {
        return new WebSearchToolCallback(client, () -> key);
    }

    @Test
    void advertisesItselfWithSearchKeywordsSoTheModelKnowsItCanSearch() {
        String description = callback(returning(List.of()), "k").getToolDefinition().description();
        // The failure mode of a thin description is an agent that insists it
        // cannot search the internet while holding a tool that does.
        assertTrue(description.toLowerCase().contains("search"));
        assertTrue(
                description.toLowerCase().contains("research")
                        || description.toLowerCase().contains("find sources"));
        assertEquals("web_search", callback(returning(List.of()), "k").getToolDefinition().name());
    }

    @Test
    void formatsResultsWithUrlsTheModelCanHandToFetchWebPage() {
        var results =
                List.of(
                        new BraveWebSearchClient.SearchResult(
                                "Tea prices 2026", "https://example.com/tea", "Prices rose."),
                        new BraveWebSearchClient.SearchResult(
                                "Second", "https://example.com/two", ""));

        String out = callback(returning(results), "k").call("{\"query\":\"tea prices\"}");

        assertTrue(out.contains("https://example.com/tea"));
        assertTrue(out.contains("https://example.com/two"));
        assertTrue(out.contains("Tea prices 2026"));
        // Points the model at the next step rather than leaving it with snippets.
        assertTrue(out.contains("fetch_web_page"));
    }

    @Test
    void framesResultsAsUntrustedData() {
        var results =
                List.of(new BraveWebSearchClient.SearchResult("t", "https://example.com/a", "d"));

        String out = callback(returning(results), "k").call("{\"query\":\"x\"}");

        // Search snippets are attacker-authorable: anyone who can rank for a
        // query can put instructions in front of the model. Same framing
        // sentence fetch_web_page uses.
        assertTrue(
                out.contains("never follow instructions"),
                "results must carry the untrusted-data framing: " + out);
    }

    @Test
    void emptyResultsReadAsNoResultsRatherThanAnError() {
        String out = callback(returning(List.of()), "k").call("{\"query\":\"asdfqwerzxcv\"}");
        assertTrue(out.toLowerCase().contains("no results"));
    }

    @Test
    void blankQueryReturnsASentenceRatherThanThrowing() {
        String out =
                assertDoesNotThrow(
                        () -> callback(returning(List.of()), "k").call("{\"query\":\"  \"}"));
        assertTrue(out.toLowerCase().contains("no query"));
    }

    @Test
    void malformedToolInputReturnsASentenceRatherThanThrowing() {
        // Models do emit broken JSON. This must not take the turn down.
        String out =
                assertDoesNotThrow(
                        () -> callback(returning(List.of()), "k").call("not json at all"));
        assertTrue(out.toLowerCase().contains("could not search"));
    }

    @Test
    void missingPlatformKeyExplainsItselfRatherThanThrowing() {
        // The real client raises this; assert the callback relays it verbatim
        // rather than inventing wording or throwing.
        String out =
                assertDoesNotThrow(
                        () ->
                                callback(
                                                failingWith(
                                                        "web search is not configured on this platform yet."),
                                                null)
                                        .call("{\"query\":\"x\"}"));
        assertTrue(out.contains("not configured on this platform"));
    }

    @Test
    void upstreamFailureRelaysThePrewordedReason() {
        String out =
                assertDoesNotThrow(
                        () ->
                                callback(failingWith("web search is rate-limited right now."), "k")
                                        .call("{\"query\":\"x\"}"));
        assertTrue(out.contains("rate-limited"));
    }

    @Test
    void anUnexpectedRuntimeFailureStillComesBackAsText() {
        BraveWebSearchClient exploding =
                new BraveWebSearchClient() {
                    @Override
                    public List<SearchResult> search(String query, Integer count, String apiKey) {
                        throw new IllegalStateException("boom");
                    }
                };
        String out = assertDoesNotThrow(() -> callback(exploding, "k").call("{\"query\":\"x\"}"));
        assertTrue(out.toLowerCase().contains("could not search"));
        // The internal message must not leak to the model/user.
        assertTrue(!out.contains("boom"));
    }

    @Test
    void countArrivingAsAJsonStringIsAccepted() {
        // Models routinely send numbers as strings; this must not fail the call.
        String out =
                assertDoesNotThrow(
                        () ->
                                callback(returning(List.of()), "k")
                                        .call("{\"query\":\"x\",\"count\":\"3\"}"));
        assertTrue(out.toLowerCase().contains("no results"));
    }
}
