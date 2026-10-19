package com.agenticai.core.agents.tools.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpRequest;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/**
 * The Tavily / Exa / Serper / SearXNG adapters against canned provider JSON: request shape
 * (endpoint, auth header, body), result mapping into the shared {@link
 * BraveWebSearchClient.SearchResult}, result capping, and the consistent error sentences. No
 * network: a fake {@link WebSearchHttp.Transport} stands in.
 */
class WebSearchProviderAdaptersTest {

    /** Records the request it was handed and answers with a canned reply. */
    private static final class Fake implements WebSearchHttp.Transport {
        final AtomicReference<HttpRequest> last = new AtomicReference<>();
        final AtomicInteger calls = new AtomicInteger();
        private final int status;
        private final String body;
        private final IOException failure;

        Fake(int status, String body) {
            this.status = status;
            this.body = body;
            this.failure = null;
        }

        Fake(IOException failure) {
            this.status = 0;
            this.body = null;
            this.failure = failure;
        }

        @Override
        public WebSearchHttp.Reply send(HttpRequest request) throws IOException {
            calls.incrementAndGet();
            last.set(request);
            if (failure != null) {
                throw failure;
            }
            return new WebSearchHttp.Reply(status, body);
        }
    }

    // ------------------------------------------------------------------ Tavily

    private static final String TAVILY_BODY =
            """
        {"query":"tea","results":[
          {"title":"Tea prices 2026","url":"https://example.com/tea","content":"Prices rose.","score":0.9,"published_date":"2026-01-15T00:00:00Z"},
          {"title":"No url","url":"","content":"skipped"},
          {"title":"Bad scheme","url":"javascript:alert(1)","content":"skipped"},
          {"title":"Second","url":"https://example.com/two","content":null,"published_date":null}
        ],"response_time":0.5}
        """;

    @Test
    void tavilyBuildsTheDocumentedRequestAndMapsResults() {
        Fake transport = new Fake(200, TAVILY_BODY);
        TavilyWebSearchProvider provider =
                new TavilyWebSearchProvider(() -> "tvly-secret", transport);

        List<BraveWebSearchClient.SearchResult> results = provider.search("tea prices", 3);

        HttpRequest sent = transport.last.get();
        assertThat(sent.method()).isEqualTo("POST");
        assertThat(sent.uri()).isEqualTo(URI.create("https://api.tavily.com/search"));
        assertThat(sent.headers().firstValue("Authorization")).contains("Bearer tvly-secret");
        assertThat(sent.headers().firstValue("Content-Type")).contains("application/json");
        String json = provider.requestJson("tea prices", 3);
        assertThat(json)
                .contains("\"query\":\"tea prices\"")
                .contains("\"max_results\":3")
                .contains("\"search_depth\":\"basic\"")
                .contains("\"include_answer\":false");
        // The key never travels in the body or the URL.
        assertThat(json).doesNotContain("tvly-secret");
        assertThat(sent.uri().toString()).doesNotContain("tvly-secret");

        assertThat(results).hasSize(2);
        assertThat(results.get(0))
                .isEqualTo(
                        new BraveWebSearchClient.SearchResult(
                                "Tea prices 2026",
                                "https://example.com/tea",
                                "Prices rose.",
                                "2026-01-15"));
        assertThat(results.get(1).url()).isEqualTo("https://example.com/two");
        assertThat(results.get(1).description()).isEmpty();
        assertThat(results.get(1).date()).isNull();
    }

    @Test
    void tavilyQueryWithQuotesCannotBreakOutOfTheJsonBody() {
        TavilyWebSearchProvider provider =
                new TavilyWebSearchProvider(() -> "k", new Fake(200, "{}"));
        String json = provider.requestJson("a\" , \"include_raw_content\": true, \"x\":\"", 5);
        assertThat(json).contains("\"include_raw_content\":false");
        assertThat(json).contains("\\\"include_raw_content\\\"");
    }

    @Test
    void tavilyPlanLimitStatusesReadAsQuotaUsedUp() {
        for (int status : new int[] {432, 433}) {
            TavilyWebSearchProvider provider =
                    new TavilyWebSearchProvider(() -> "k", new Fake(status, "{}"));
            assertThatThrownBy(() -> provider.search("q", 5))
                    .isInstanceOf(WebSearchException.class)
                    .hasMessageContaining("quota is used up")
                    .hasMessageContaining("Tavily");
        }
    }

    // --------------------------------------------------------------------- Exa

    private static final String EXA_BODY =
            """
        {"requestId":"r","results":[
          {"title":"Exa one","url":"https://example.com/exa1","publishedDate":"2024-01-15T00:00:00.000Z",
           "highlights":["first excerpt","second excerpt"]},
          {"title":"Exa two","url":"https://example.com/exa2","highlights":[],"summary":"A summary."},
          {"title":"Exa three","url":"https://example.com/exa3","text":"Leading page text."}
        ],"costDollars":{"total":0.007}}
        """;

    @Test
    void exaBuildsTheDocumentedRequestAndMapsHighlightsToSnippets() {
        Fake transport = new Fake(200, EXA_BODY);
        ExaWebSearchProvider provider = new ExaWebSearchProvider(() -> "exa-secret", transport);

        List<BraveWebSearchClient.SearchResult> results = provider.search("batteries", 10);

        HttpRequest sent = transport.last.get();
        assertThat(sent.method()).isEqualTo("POST");
        assertThat(sent.uri()).isEqualTo(URI.create("https://api.exa.ai/search"));
        assertThat(sent.headers().firstValue("x-api-key")).contains("exa-secret");
        String json = provider.requestJson("batteries", 10);
        assertThat(json)
                .contains("\"query\":\"batteries\"")
                .contains("\"numResults\":10")
                .contains("\"contents\":{\"highlights\":true}");

        assertThat(results).hasSize(3);
        assertThat(results.get(0).description()).isEqualTo("first excerpt ... second excerpt");
        assertThat(results.get(0).date()).isEqualTo("2024-01-15");
        assertThat(results.get(1).description()).isEqualTo("A summary.");
        assertThat(results.get(2).description()).isEqualTo("Leading page text.");
    }

    @Test
    void exaOutOfCreditsIsAQuotaSentence() {
        ExaWebSearchProvider provider = new ExaWebSearchProvider(() -> "k", new Fake(402, "{}"));
        assertThatThrownBy(() -> provider.search("q", 5))
                .isInstanceOf(WebSearchException.class)
                .hasMessageContaining("quota is used up");
    }

    // ------------------------------------------------------------------ Serper

    private static final String SERPER_BODY =
            """
        {"searchParameters":{"q":"tea"},"organic":[
          {"title":"Serper one","link":"https://example.com/s1","snippet":"Snippet one.","date":"Jan 5, 2025","position":1},
          {"title":"Serper two","link":"https://example.com/s2","snippet":"Snippet two.","position":2},
          {"title":"Serper three","link":"https://example.com/s3","snippet":"Snippet three.","position":3}
        ]}
        """;

    @Test
    void serperBuildsTheDocumentedRequestAndCapsResultsAtCount() {
        Fake transport = new Fake(200, SERPER_BODY);
        SerperWebSearchProvider provider =
                new SerperWebSearchProvider(() -> "serper-secret", transport);

        List<BraveWebSearchClient.SearchResult> results = provider.search("tea", 2);

        HttpRequest sent = transport.last.get();
        assertThat(sent.method()).isEqualTo("POST");
        assertThat(sent.uri()).isEqualTo(URI.create("https://google.serper.dev/search"));
        assertThat(sent.headers().firstValue("X-API-KEY")).contains("serper-secret");
        assertThat(provider.requestJson("tea", 2)).isEqualTo("{\"q\":\"tea\",\"num\":2}");

        assertThat(results).hasSize(2);
        assertThat(results.get(0))
                .isEqualTo(
                        new BraveWebSearchClient.SearchResult(
                                "Serper one",
                                "https://example.com/s1",
                                "Snippet one.",
                                "Jan 5, 2025"));
    }

    // ------------------------------------------------- shared error semantics

    @Test
    void everyKeyedAdapterRefusesWithoutAKeyAndNeverCallsTheNetwork() {
        Fake transport = new Fake(200, "{}");
        List<WebSearchProvider> providers =
                List.of(
                        new TavilyWebSearchProvider(() -> null, transport),
                        new ExaWebSearchProvider(() -> "  ", transport),
                        new SerperWebSearchProvider(
                                () -> {
                                    throw new IllegalStateException("cannot decrypt");
                                },
                                transport));
        for (WebSearchProvider p : providers) {
            assertThat(p.configured()).as(p.id()).isFalse();
            assertThatThrownBy(() -> p.search("q", 5))
                    .isInstanceOf(WebSearchException.class)
                    .hasMessageContaining("not configured on this platform");
        }
        assertThat(transport.calls.get()).isZero();
    }

    @Test
    void blankQueryIsRefusedWithBravesSentenceBeforeAnyRequest() {
        Fake transport = new Fake(200, "{}");
        TavilyWebSearchProvider provider = new TavilyWebSearchProvider(() -> "k", transport);
        assertThatThrownBy(() -> provider.search("   ", 5))
                .isInstanceOf(WebSearchException.class)
                .hasMessage("no search query was provided.");
        assertThat(transport.calls.get()).isZero();
    }

    @Test
    void rejectedKeyRateLimitAndOutageUseTheSameSentencesAsBraveAndNeverEchoTheBodyOrKey() {
        TavilyWebSearchProvider rejected =
                new TavilyWebSearchProvider(
                        () -> "tvly-secret", new Fake(401, "{\"detail\":\"bad key tvly-secret\"}"));
        assertThatThrownBy(() -> rejected.search("q", 5))
                .isInstanceOf(WebSearchException.class)
                .hasMessageContaining("key was rejected")
                .hasMessageNotContaining("tvly-secret");

        ExaWebSearchProvider limited = new ExaWebSearchProvider(() -> "k", new Fake(429, "{}"));
        assertThatThrownBy(() -> limited.search("q", 5)).hasMessageContaining("rate-limited");

        SerperWebSearchProvider down =
                new SerperWebSearchProvider(() -> "k", new Fake(503, "<html>oops</html>"));
        assertThatThrownBy(() -> down.search("q", 5))
                .hasMessageContaining("failed (HTTP 503)")
                .hasMessageNotContaining("oops");

        TavilyWebSearchProvider unreachable =
                new TavilyWebSearchProvider(
                        () -> "k", new Fake(new IOException("connection reset")));
        assertThatThrownBy(() -> unreachable.search("q", 5))
                .hasMessageContaining("could not reach the search service")
                .hasMessageNotContaining("connection reset");
    }

    @Test
    void anUnreadableBodyDegradesToNoResultsNotAThrow() {
        TavilyWebSearchProvider provider =
                new TavilyWebSearchProvider(() -> "k", new Fake(200, "<<not json>>"));
        assertThat(provider.search("q", 5)).isEmpty();
        ExaWebSearchProvider shapeChange =
                new ExaWebSearchProvider(() -> "k", new Fake(200, "{\"data\":[]}"));
        assertThat(shapeChange.search("q", 5)).isEmpty();
    }

    @Test
    void countIsClampedToTheSharedBounds() {
        SerperWebSearchProvider provider =
                new SerperWebSearchProvider(() -> "k", new Fake(200, "{}"));
        assertThat(WebSearchHttp.clampCount(null)).isEqualTo(5);
        assertThat(WebSearchHttp.clampCount(0)).isEqualTo(1);
        assertThat(WebSearchHttp.clampCount(99)).isEqualTo(10);
        assertThat(provider.search("q", 99)).isEmpty();
    }

    // ----------------------------------------------------------------- SearXNG

    private static final String SEARXNG_BODY =
            """
        {"query":"tea","results":[
          {"title":"One","url":"https://example.com/1","content":"First snippet.","publishedDate":"2025-03-01T10:00:00"},
          {"title":"Internal","url":"file:///etc/passwd","content":"dropped"},
          {"title":"Two","url":"http://example.com/2","content":"Second snippet.","publishedDate":null},
          {"title":"Three","url":"https://example.com/3","content":"Third snippet."}
        ],"unresponsive_engines":[]}
        """;

    private static SearxngWebSearchProvider searxng(
            String baseUrl, WebSearchHttp.Transport transport) {
        return new SearxngWebSearchProvider(baseUrl, "", "general", "", 10, transport);
    }

    @Test
    void searxngBuildsAJsonSearchUrlOnTheConfiguredHostOnly() {
        Fake transport = new Fake(200, SEARXNG_BODY);
        SearxngWebSearchProvider provider =
                new SearxngWebSearchProvider(
                        "http://searxng:8080/",
                        "duckduckgo,brave",
                        "general,news",
                        "en",
                        10,
                        transport);

        List<BraveWebSearchClient.SearchResult> results = provider.search("tea & biscuits?", 2);

        HttpRequest sent = transport.last.get();
        assertThat(sent.method()).isEqualTo("GET");
        assertThat(sent.uri().getScheme()).isEqualTo("http");
        assertThat(sent.uri().getHost()).isEqualTo("searxng");
        assertThat(sent.uri().getPort()).isEqualTo(8080);
        assertThat(sent.uri().getRawPath()).isEqualTo("/search");
        assertThat(sent.uri().getRawQuery())
                .contains("q=tea+%26+biscuits%3F")
                .contains("format=json")
                .contains("categories=general%2Cnews")
                .contains("engines=duckduckgo%2Cbrave")
                .contains("language=en");
        assertThat(provider.isConfiguredOrigin(sent.uri())).isTrue();

        // file: result dropped, the rest capped at the requested count, dates shortened.
        assertThat(results).hasSize(2);
        assertThat(results.get(0).url()).isEqualTo("https://example.com/1");
        assertThat(results.get(0).date()).isEqualTo("2025-03-01");
        assertThat(results.get(1).url()).isEqualTo("http://example.com/2");
    }

    @Test
    void searxngKeepsAPathPrefixAndToleratesAPastedSearchUrl() {
        Fake transport = new Fake(200, "{}");
        searxng("https://search.internal.example/sx/search", transport).search("q", 5);
        assertThat(transport.last.get().uri().toString())
                .startsWith("https://search.internal.example/sx/search?q=q&format=json");
    }

    @Test
    void searxngExemptionIsScopedToTheConfiguredOriginOnly() {
        SearxngWebSearchProvider provider = searxng("http://searxng:8080", new Fake(200, "{}"));
        assertThat(provider.isConfiguredOrigin(URI.create("http://searxng:8080/search?q=x")))
                .isTrue();
        assertThat(provider.isConfiguredOrigin(URI.create("http://SEARXNG:8080/search"))).isTrue();
        // anything else - another host, port, scheme, userinfo trick, metadata address - is out of
        // scope
        assertThat(provider.isConfiguredOrigin(URI.create("http://evil.example:8080/search")))
                .isFalse();
        assertThat(provider.isConfiguredOrigin(URI.create("http://searxng:9090/search"))).isFalse();
        assertThat(provider.isConfiguredOrigin(URI.create("https://searxng:8080/search")))
                .isFalse();
        assertThat(
                        provider.isConfiguredOrigin(
                                URI.create("http://searxng@evil.example:8080/search")))
                .isFalse();
        assertThat(provider.isConfiguredOrigin(URI.create("http://127.0.0.1:8080/search")))
                .isFalse();
        assertThat(
                        provider.isConfiguredOrigin(
                                URI.create("http://169.254.169.254/latest/meta-data")))
                .isFalse();
        assertThat(provider.isConfiguredOrigin(null)).isFalse();
    }

    @Test
    void searxngRejectsUnsafeOrMalformedBaseUrlsAndBecomesNotConfigured() {
        for (String bad :
                new String[] {
                    null,
                    "",
                    "   ",
                    "searxng:8080",
                    "ftp://searxng:8080",
                    "file:///etc",
                    "http://",
                    "http://user:pass@searxng:8080",
                    "http://searxng:8080?x=1",
                    "http://searxng:8080/#frag",
                    "http://169.254.169.254/",
                    "http://metadata.google.internal/"
                }) {
            SearxngWebSearchProvider provider = searxng(bad, new Fake(200, "{}"));
            assertThat(provider.configured()).as("base url: " + bad).isFalse();
            assertThatThrownBy(() -> provider.search("q", 5))
                    .isInstanceOf(WebSearchException.class)
                    .hasMessageContaining("app.web-search.searxng.base-url");
        }
        // Internal addresses are the whole point of the exemption and must be accepted.
        assertThat(searxng("http://searxng:8080", new Fake(200, "{}")).configured()).isTrue();
        assertThat(searxng("http://127.0.0.1:8888", new Fake(200, "{}")).configured()).isTrue();
        assertThat(searxng("http://10.0.4.7:8080", new Fake(200, "{}")).configured()).isTrue();
    }

    @Test
    void searxngNeverCallsTheNetworkWhenNotConfigured() {
        Fake transport = new Fake(200, "{}");
        assertThatThrownBy(() -> searxng("", transport).search("q", 5))
                .isInstanceOf(WebSearchException.class);
        assertThat(transport.calls.get()).isZero();
    }

    @Test
    void searxngForbiddenReadsAsAConfigurationProblemNotABadKey() {
        SearxngWebSearchProvider provider =
                searxng("http://searxng:8080", new Fake(403, "Forbidden"));
        assertThatThrownBy(() -> provider.search("q", 5))
                .isInstanceOf(WebSearchException.class)
                .hasMessageContaining("JSON output format may be disabled")
                .hasMessageNotContaining("key");
    }

    @Test
    void searxngResultsAreCappedByTheConfiguredMaximum() {
        StringBuilder body = new StringBuilder("{\"results\":[");
        for (int i = 0; i < 30; i++) {
            body.append(i == 0 ? "" : ",")
                    .append("{\"title\":\"t")
                    .append(i)
                    .append("\",\"url\":\"https://example.com/")
                    .append(i)
                    .append("\",\"content\":\"c\"}");
        }
        body.append("]}");
        SearxngWebSearchProvider provider =
                new SearxngWebSearchProvider(
                        "http://searxng:8080",
                        "",
                        "general",
                        "",
                        3,
                        new Fake(200, body.toString()));
        assertThat(provider.search("q", 10)).hasSize(3);
    }

    @Test
    void searxngSnippetsAreClippedBeforeTheyReachThePrompt() {
        String longText = "x".repeat(5000);
        String body =
                "{\"results\":[{\"title\":\""
                        + longText
                        + "\",\"url\":\"https://example.com/a\",\"content\":\""
                        + longText
                        + "\"}]}";
        List<BraveWebSearchClient.SearchResult> results =
                searxng("http://searxng:8080", new Fake(200, body)).search("q", 5);
        assertThat(results.get(0).title()).hasSize(WebSearchHttp.MAX_TITLE_CHARS);
        assertThat(results.get(0).description()).hasSize(WebSearchHttp.MAX_SNIPPET_CHARS);
    }
}
