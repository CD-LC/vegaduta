package com.agenticai.core.agents.tools.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * Provider selection, fallback ordering, daily caps, honest notes and the flag-off identity of
 * {@link WebSearchProviderRegistry}. Fake providers and an in-memory counter: no network, no Redis.
 */
class WebSearchProviderRegistryTest {

    /**
     * A scripted provider: configured or not, answering with results or failing with a sentence.
     */
    private static final class Fake implements WebSearchProvider {
        final String id;
        final boolean configured;
        final List<BraveWebSearchClient.SearchResult> results;
        final String failure;
        final AtomicInteger calls = new AtomicInteger();

        Fake(
                String id,
                boolean configured,
                List<BraveWebSearchClient.SearchResult> results,
                String failure) {
            this.id = id;
            this.configured = configured;
            this.results = results;
            this.failure = failure;
        }

        static Fake answering(String id, String url) {
            return new Fake(
                    id,
                    true,
                    List.of(new BraveWebSearchClient.SearchResult(id + " result", url, "snippet")),
                    null);
        }

        static Fake failing(String id, String message) {
            return new Fake(id, true, List.of(), message);
        }

        static Fake unconfigured(String id) {
            return new Fake(id, false, List.of(), null);
        }

        @Override
        public String id() {
            return id;
        }

        @Override
        public String label() {
            return id.toUpperCase();
        }

        @Override
        public boolean configured() {
            return configured;
        }

        @Override
        public String notConfiguredMessage() {
            return "web search is not configured on this platform yet (" + id + ").";
        }

        @Override
        public List<BraveWebSearchClient.SearchResult> search(String query, Integer count) {
            calls.incrementAndGet();
            if (failure != null) {
                throw new WebSearchException(failure);
            }
            return results;
        }
    }

    /** In-memory stand-in for the Redis window counter. */
    private static final class MemoryCounter implements WebSearchProviderRegistry.CapCounter {
        final Map<String, Long> values = new HashMap<>();
        int adds;
        int undos;
        boolean failOpen;

        @Override
        public long add(String key, long by) {
            adds++;
            if (failOpen) {
                return 0;
            }
            return values.merge(key, by, Long::sum);
        }

        @Override
        public void undo(String key, long by) {
            undos++;
            values.merge(key, -by, Long::sum);
        }

        @Override
        public long current(String key) {
            return values.getOrDefault(key, 0L);
        }
    }

    private static List<WebSearchProvider> all(WebSearchProvider... providers) {
        return new ArrayList<>(List.of(providers));
    }

    private static WebSearchProviderRegistry registry(
            List<WebSearchProvider> providers,
            String primary,
            String fallbackCsv,
            Map<String, Integer> caps,
            WebSearchProviderRegistry.CapCounter counter) {
        return new WebSearchProviderRegistry(
                providers,
                primary,
                WebSearchProviderRegistry.parseList(fallbackCsv),
                caps,
                counter);
    }

    // ------------------------------------------------------ identity / defaults

    @Test
    void shippedDefaultsAreInactiveSoTheResolverKeepsTheOriginalBravePath() {
        Fake brave = Fake.answering("brave", "https://example.com/b");
        Fake searxng = Fake.answering("searxng", "https://example.com/s");
        WebSearchProviderRegistry r = registry(all(brave, searxng), "brave", "", Map.of(), null);

        assertThat(r.multiProviderActive()).isFalse();
        // Only Brave is in the chain: a configured SearXNG must NOT make search look available.
        Fake unconfiguredBrave = Fake.unconfigured("brave");
        WebSearchProviderRegistry inactive =
                registry(all(unconfiguredBrave, searxng), "brave", "", Map.of(), null);
        assertThat(inactive.multiProviderActive()).isFalse();
        assertThat(inactive.searchAvailable(() -> true))
                .isTrue(); // the original Brave-key test decides
        assertThat(inactive.searchAvailable(() -> false)).isFalse();
    }

    @Test
    void blankOrUnknownPrimaryFallsBackToBraveAndStaysInactive() {
        Fake brave = Fake.answering("brave", "https://example.com/b");
        assertThat(registry(all(brave), null, "", Map.of(), null).multiProviderActive()).isFalse();
        assertThat(registry(all(brave), "  ", "", Map.of(), null).multiProviderActive()).isFalse();
        assertThat(registry(all(brave), "altavista", "", Map.of(), null).multiProviderActive())
                .isFalse();
        assertThat(registry(all(brave), "altavista", "", Map.of(), null).chain())
                .containsExactly(brave);
    }

    @Test
    void aNonBravePrimaryOrAnyFallbackActivatesTheLayer() {
        Fake brave = Fake.answering("brave", "https://example.com/b");
        Fake tavily = Fake.answering("tavily", "https://example.com/t");
        assertThat(registry(all(brave, tavily), "tavily", "", Map.of(), null).multiProviderActive())
                .isTrue();
        assertThat(
                        registry(all(brave, tavily), "brave", "tavily", Map.of(), null)
                                .multiProviderActive())
                .isTrue();
    }

    @Test
    void chainIsPrimaryThenFallbacksWithUnknownDuplicatesAndThePrimaryDropped() {
        Fake brave = Fake.answering("brave", "u");
        Fake tavily = Fake.answering("tavily", "u");
        Fake exa = Fake.answering("exa", "u");
        Fake serper = Fake.answering("serper", "u");
        WebSearchProviderRegistry r =
                registry(
                        all(brave, tavily, exa, serper),
                        "Tavily",
                        " exa , nope, tavily, serper, exa ,",
                        Map.of(),
                        null);
        assertThat(r.chain()).containsExactly(tavily, exa, serper);
    }

    @Test
    void searchAvailableWhenActiveMeansAnyProviderInTheChainIsConfigured() {
        Fake brave = Fake.unconfigured("brave");
        Fake searxng = Fake.answering("searxng", "u");
        WebSearchProviderRegistry active =
                registry(all(brave, searxng), "searxng", "", Map.of(), null);
        // The Brave-key supplier must be ignored once the layer is active.
        assertThat(
                        active.searchAvailable(
                                () -> {
                                    throw new AssertionError(
                                            "the Brave check must not run in multi-provider mode");
                                }))
                .isTrue();
        WebSearchProviderRegistry none =
                registry(
                        all(Fake.unconfigured("brave"), Fake.unconfigured("exa")),
                        "brave",
                        "exa",
                        Map.of(),
                        null);
        assertThat(none.searchAvailable(() -> true)).isFalse();
    }

    // ------------------------------------------------------------- selection

    @Test
    void thePrimaryAnswersWithoutANoteAndNobodyElseIsCalled() {
        Fake tavily = Fake.answering("tavily", "https://example.com/t");
        Fake exa = Fake.answering("exa", "https://example.com/e");
        WebSearchProviderRegistry r =
                registry(
                        all(Fake.answering("brave", "u"), tavily, exa),
                        "tavily",
                        "exa",
                        Map.of(),
                        null);

        WebSearchProviderRegistry.Outcome out = r.search("tea", 5);

        assertThat(out.providerId()).isEqualTo("tavily");
        assertThat(out.note()).isNull();
        assertThat(out.results())
                .extracting(BraveWebSearchClient.SearchResult::url)
                .containsExactly("https://example.com/t");
        assertThat(tavily.calls.get()).isEqualTo(1);
        assertThat(exa.calls.get()).isZero();
    }

    @Test
    void aFailingPrimaryDegradesToTheNextProviderInOrderWithAnHonestNote() {
        Fake brave =
                Fake.failing("brave", "web search is rate-limited right now. Try again shortly.");
        Fake tavily = Fake.failing("tavily", "web search failed (HTTP 502). Try again shortly.");
        Fake exa = Fake.answering("exa", "https://example.com/e");
        Fake serper = Fake.answering("serper", "https://example.com/s");
        WebSearchProviderRegistry r =
                registry(
                        all(brave, tavily, exa, serper),
                        "brave",
                        "tavily,exa,serper",
                        Map.of(),
                        null);

        WebSearchProviderRegistry.Outcome out = r.search("tea", 5);

        assertThat(out.providerId()).isEqualTo("exa");
        assertThat(serper.calls.get())
                .as("later fallbacks are not called once one answers")
                .isZero();
        assertThat(out.note())
                .startsWith("Note: ")
                .contains("BRAVE: web search is rate-limited right now")
                .contains("TAVILY: web search failed (HTTP 502)")
                .contains("The results below come from EXA instead.");
    }

    @Test
    void anUnconfiguredPrimaryIsSkippedWithoutBeingCalled() {
        Fake brave = Fake.unconfigured("brave");
        Fake searxng = Fake.answering("searxng", "https://example.com/s");
        WebSearchProviderRegistry r =
                registry(all(brave, searxng), "brave", "searxng", Map.of(), null);

        WebSearchProviderRegistry.Outcome out = r.search("tea", 5);

        assertThat(out.providerId()).isEqualTo("searxng");
        assertThat(brave.calls.get()).isZero();
        assertThat(out.note()).contains("BRAVE: not configured").contains("SEARXNG instead");
    }

    @Test
    void anEmptyResultIsARealAnswerAndDoesNotFallThrough() {
        Fake tavily = new Fake("tavily", true, List.of(), null);
        Fake exa = Fake.answering("exa", "https://example.com/e");
        WebSearchProviderRegistry r =
                registry(
                        all(Fake.answering("brave", "u"), tavily, exa),
                        "tavily",
                        "exa",
                        Map.of(),
                        null);

        WebSearchProviderRegistry.Outcome out = r.search("asdfqwer", 5);

        assertThat(out.results()).isEmpty();
        assertThat(out.providerId()).isEqualTo("tavily");
        assertThat(exa.calls.get()).isZero();
    }

    @Test
    void everyProviderFailingListsEachReasonAndNeverThrowsAnythingElse() {
        Fake brave = Fake.unconfigured("brave");
        Fake tavily =
                Fake.failing("tavily", "web search is rate-limited right now. Try again shortly.");
        WebSearchProviderRegistry r =
                registry(all(brave, tavily), "brave", "tavily", Map.of(), null);

        assertThatThrownBy(() -> r.search("tea", 5))
                .isInstanceOf(WebSearchException.class)
                .hasMessageStartingWith("no search provider could answer (")
                .hasMessageContaining("BRAVE: not configured")
                .hasMessageContaining("TAVILY: web search is rate-limited right now");
    }

    @Test
    void aSingleProviderChainRelaysThatProvidersOwnSentenceUnchanged() {
        Fake tavily =
                Fake.failing("tavily", "web search is rate-limited right now. Try again shortly.");
        WebSearchProviderRegistry failing =
                registry(all(Fake.answering("brave", "u"), tavily), "tavily", "", Map.of(), null);
        assertThatThrownBy(() -> failing.search("tea", 5))
                .hasMessage("web search is rate-limited right now. Try again shortly.");

        Fake exa = Fake.unconfigured("exa");
        WebSearchProviderRegistry unconfigured =
                registry(all(Fake.answering("brave", "u"), exa), "exa", "", Map.of(), null);
        assertThatThrownBy(() -> unconfigured.search("tea", 5))
                .hasMessage("web search is not configured on this platform yet (exa).");
    }

    @Test
    void aBlankQueryIsRefusedBeforeAnyProviderIsTouched() {
        Fake tavily = Fake.answering("tavily", "u");
        WebSearchProviderRegistry r =
                registry(all(Fake.answering("brave", "u"), tavily), "tavily", "", Map.of(), null);
        assertThatThrownBy(() -> r.search("  ", 5)).hasMessage("no search query was provided.");
        assertThatThrownBy(() -> r.search(null, 5)).hasMessage("no search query was provided.");
        assertThat(tavily.calls.get()).isZero();
    }

    // ------------------------------------------------------------------ caps

    @Test
    void aDailyCapSkipsTheProviderOnceSpentAndTheNoteSaysSo() {
        Fake tavily = Fake.answering("tavily", "https://example.com/t");
        Fake exa = Fake.answering("exa", "https://example.com/e");
        MemoryCounter counter = new MemoryCounter();
        WebSearchProviderRegistry r =
                registry(
                        all(Fake.answering("brave", "u"), tavily, exa),
                        "tavily",
                        "exa",
                        Map.of("tavily", 2),
                        counter);

        assertThat(r.search("a", 5).providerId()).isEqualTo("tavily");
        assertThat(r.search("b", 5).providerId()).isEqualTo("tavily");
        WebSearchProviderRegistry.Outcome third = r.search("c", 5);

        assertThat(third.providerId()).isEqualTo("exa");
        assertThat(third.note()).contains("TAVILY: daily limit reached");
        assertThat(tavily.calls.get()).isEqualTo(2);
        // The refused attempt was handed back, so the window still reads exactly the cap.
        assertThat(counter.current(WebSearchProviderRegistry.CAP_KEY_PREFIX + "tavily"))
                .isEqualTo(2);
        assertThat(counter.undos).isEqualTo(1);
    }

    @Test
    void aSpentCapOnTheOnlyProviderSaysSoPlainly() {
        MemoryCounter counter = new MemoryCounter();
        counter.values.put(WebSearchProviderRegistry.CAP_KEY_PREFIX + "exa", 5L);
        WebSearchProviderRegistry r =
                registry(
                        all(Fake.answering("brave", "u"), Fake.answering("exa", "u")),
                        "exa",
                        "",
                        Map.of("exa", 5),
                        counter);
        assertThatThrownBy(() -> r.search("q", 5))
                .hasMessageContaining("daily limit for EXA")
                .hasMessageContaining("fetch_web_page");
    }

    @Test
    void noCapMeansTheCounterIsNeverTouched() {
        MemoryCounter counter = new MemoryCounter();
        WebSearchProviderRegistry r =
                registry(
                        all(Fake.answering("brave", "u"), Fake.answering("tavily", "u")),
                        "tavily",
                        "",
                        Map.of("tavily", 0),
                        counter);
        r.search("q", 5);
        assertThat(counter.adds).isZero();
    }

    @Test
    void anUnavailableCounterFailsOpenSoSearchKeepsWorking() {
        MemoryCounter counter = new MemoryCounter();
        counter.failOpen = true;
        Fake tavily = Fake.answering("tavily", "u");
        WebSearchProviderRegistry r =
                registry(
                        all(Fake.answering("brave", "u"), tavily),
                        "tavily",
                        "",
                        Map.of("tavily", 1),
                        counter);
        for (int i = 0; i < 5; i++) {
            assertThat(r.search("q", 5).providerId()).isEqualTo("tavily");
        }
        assertThat(tavily.calls.get()).isEqualTo(5);
    }

    @Test
    void capsAreCountedPerProvider() {
        MemoryCounter counter = new MemoryCounter();
        WebSearchProviderRegistry r =
                registry(
                        all(
                                Fake.answering("brave", "u"),
                                Fake.answering("tavily", "u"),
                                Fake.answering("exa", "u")),
                        "tavily",
                        "exa",
                        Map.of("tavily", 1, "exa", 1),
                        counter);
        r.search("a", 5);
        assertThat(counter.current(WebSearchProviderRegistry.CAP_KEY_PREFIX + "tavily"))
                .isEqualTo(1);
        assertThat(counter.current(WebSearchProviderRegistry.CAP_KEY_PREFIX + "exa")).isZero();
    }

    // ---------------------------------------------------------------- status

    @Test
    void statusReportsRolesConfiguredStateCapsAndWhoAnswersNow() {
        MemoryCounter counter = new MemoryCounter();
        counter.values.put(WebSearchProviderRegistry.CAP_KEY_PREFIX + "tavily", 3L);
        WebSearchProviderRegistry r =
                registry(
                        all(
                                Fake.unconfigured("brave"),
                                Fake.answering("searxng", "u"),
                                Fake.answering("tavily", "u"),
                                Fake.answering("exa", "u")),
                        "tavily",
                        "brave,exa",
                        Map.of("tavily", 3),
                        counter);

        WebSearchProviderRegistry.Status status = r.status();

        assertThat(status.multiProviderActive()).isTrue();
        assertThat(status.primary()).isEqualTo("tavily");
        assertThat(status.fallback()).containsExactly("brave", "exa");
        // tavily is configured but its cap is spent, brave is unconfigured -> exa is the one that
        // would answer.
        assertThat(status.activeNow()).isEqualTo("exa");
        Map<String, WebSearchProviderRegistry.ProviderStatus> byId = new HashMap<>();
        status.providers().forEach(p -> byId.put(p.id(), p));
        assertThat(byId.get("tavily").role()).isEqualTo("primary");
        assertThat(byId.get("tavily").dailyCap()).isEqualTo(3);
        assertThat(byId.get("tavily").usedToday()).isEqualTo(3);
        assertThat(byId.get("brave").role()).isEqualTo("fallback");
        assertThat(byId.get("brave").fallbackPosition()).isEqualTo(1);
        assertThat(byId.get("brave").configured()).isFalse();
        assertThat(byId.get("exa").fallbackPosition()).isEqualTo(2);
        assertThat(byId.get("exa").active()).isTrue();
        assertThat(byId.get("searxng").role()).isEqualTo("unused");
        assertThat(byId.get("searxng").configured()).isTrue();
    }

    @Test
    void statusInDefaultModeSaysInactiveAndNamesBraveAsTheOnlyChainMember() {
        WebSearchProviderRegistry r =
                registry(
                        all(Fake.answering("brave", "u"), Fake.answering("tavily", "u")),
                        "brave",
                        "",
                        Map.of(),
                        null);
        WebSearchProviderRegistry.Status status = r.status();
        assertThat(status.multiProviderActive()).isFalse();
        assertThat(status.activeNow()).isEqualTo("brave");
        assertThat(status.fallback()).isEmpty();
    }

    @Test
    void parseListSplitsTrimsAndDropsBlanks() {
        assertThat(WebSearchProviderRegistry.parseList(null)).isEmpty();
        assertThat(WebSearchProviderRegistry.parseList("")).isEmpty();
        assertThat(WebSearchProviderRegistry.parseList(" a, ,b ,, c"))
                .containsExactly("a", "b", "c");
    }
}
