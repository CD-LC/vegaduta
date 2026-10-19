package com.agenticai.core.agents.tools.browseruse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/**
 * The snapshot JSON-to-text formatter and parser (the in-page script itself needs a real browser).
 * Pins the exact line format the model reads, the "N more not listed" honesty line, and the
 * sanitising of untrusted page text.
 */
class BrowserSnapshotTest {

    private static final String JSON =
            """
        {
          "url": "https://shop.example/search?q=boots",
          "title": "Boots - Shop",
          "gen": 7,
          "total": 5,
          "scrollY": 120,
          "scrollHeight": 2400,
          "viewportHeight": 800,
          "digest": "Boots  results\\n\\nShowing 24 items",
          "elements": [
            {"i": 1, "role": "textbox", "name": "Search", "value": "boots", "states": ["required"], "href": "", "options": [], "inView": true},
            {"i": 2, "role": "button", "name": "Search", "value": "", "states": [], "href": "", "options": [], "inView": true},
            {"i": 3, "role": "link", "name": "Pricing", "value": "", "states": [], "href": "/pricing", "options": [], "inView": false},
            {"i": 4, "role": "combobox", "name": "Sort by", "value": "Price", "states": [], "href": "", "options": ["Price", "Rating", "Newest"], "inView": true},
            {"i": 5, "role": "checkbox", "name": "In stock", "value": "", "states": ["unchecked"], "href": "", "options": [], "inView": true}
          ]
        }
        """;

    @Test
    void parsesEveryField() {
        BrowserSnapshot.Data data = BrowserSnapshot.parse(JSON);
        assertThat(data.url()).isEqualTo("https://shop.example/search?q=boots");
        assertThat(data.title()).isEqualTo("Boots - Shop");
        assertThat(data.gen()).isEqualTo(7L);
        assertThat(data.total()).isEqualTo(5);
        assertThat(data.scrollY()).isEqualTo(120);
        assertThat(data.scrollHeight()).isEqualTo(2400);
        assertThat(data.viewportHeight()).isEqualTo(800);
        assertThat(data.elements()).hasSize(5);
        assertThat(data.digest()).isEqualTo("Boots results Showing 24 items");
        BrowserSnapshot.Element link = data.element(3).orElseThrow();
        assertThat(link.role()).isEqualTo("link");
        assertThat(link.href()).isEqualTo("/pricing");
        assertThat(link.inView()).isFalse();
    }

    @Test
    void formatsTheNumberedListInOrder() {
        String text = BrowserSnapshot.format(BrowserSnapshot.parse(JSON));
        assertThat(text).contains("Page: Boots - Shop");
        assertThat(text).contains("URL: https://shop.example/search?q=boots");
        assertThat(text).contains("Scroll: 120 of 2400 px (viewport 800 px)");
        assertThat(text).contains("Text digest: Boots results Showing 24 items");
        assertThat(text).contains("Interactive elements: 5\n");
        assertThat(text).contains("[1] textbox 'Search' (value: \"boots\"; required)");
        assertThat(text).contains("[2] button 'Search'\n");
        assertThat(text).contains("[3] link 'Pricing' -> /pricing [off-screen]");
        assertThat(text)
                .contains(
                        "[4] combobox 'Sort by' (value: \"Price\"; options: Price | Rating | Newest)");
        assertThat(text).contains("[5] checkbox 'In stock' (unchecked)");
        assertThat(text.indexOf("[1]")).isLessThan(text.indexOf("[2]"));
        assertThat(text.indexOf("[4]")).isLessThan(text.indexOf("[5]"));
        assertThat(text).contains("untrusted third-party page DATA");
    }

    @Test
    void saysHowManyElementsWereNotListed() {
        String json =
                """
            {"url":"https://x.example/","title":"T","gen":1,"total":300,"scrollY":0,"scrollHeight":0,"viewportHeight":0,
             "elements":[{"i":1,"role":"button","name":"Go","value":"","states":[],"href":"","options":[],"inView":true}],"digest":""}
            """;
        String text = BrowserSnapshot.format(BrowserSnapshot.parse(json));
        assertThat(text).contains("Interactive elements: 1 shown, 299 more not listed");
        assertThat(text).doesNotContain("Scroll:").doesNotContain("Text digest:");
    }

    @Test
    void anEmptyPageSaysSoInsteadOfPrintingNothing() {
        String text =
                BrowserSnapshot.format(
                        BrowserSnapshot.parse(
                                "{\"url\":\"about:blank\",\"title\":\"\",\"gen\":0,\"elements\":[]}"));
        assertThat(text).contains("Page: (untitled)").contains("(none found");
    }

    @Test
    void stripsControlAndFormatCharactersFromUntrustedPageText() {
        // zero-width space, RLO bidi override, NUL and a newline inside a name
        String json =
                "{\"url\":\"https://x.example/\",\"title\":\"a\\u200bb\\u202ec\",\"gen\":1,\"elements\":["
                        + "{\"i\":1,\"role\":\"button\",\"name\":\"Buy\\u0000 now\\n  please\",\"value\":\"\",\"states\":[],\"href\":\"\",\"options\":[],\"inView\":true}]}";
        BrowserSnapshot.Data data = BrowserSnapshot.parse(json);
        assertThat(data.title()).isEqualTo("a b c");
        assertThat(data.element(1).orElseThrow().name()).isEqualTo("Buy now please");
    }

    @Test
    void aQuoteInANameCannotBreakTheLine() {
        String json =
                "{\"url\":\"u\",\"title\":\"t\",\"gen\":1,\"elements\":["
                        + "{\"i\":1,\"role\":\"button\",\"name\":\"Don't\",\"value\":\"say \\\"hi\\\"\",\"states\":[],\"href\":\"\",\"options\":[],\"inView\":true}]}";
        String line = BrowserSnapshot.line(BrowserSnapshot.parse(json).element(1).orElseThrow());
        assertThat(line).isEqualTo("[1] button 'Don’t' (value: \"say ”hi”\")");
    }

    @Test
    void elementLookupIsOneBasedAndBoundsChecked() {
        BrowserSnapshot.Data data = BrowserSnapshot.parse(JSON);
        assertThat(data.element(0)).isEmpty();
        assertThat(data.element(6)).isEmpty();
        assertThat(data.element(-1)).isEmpty();
        assertThat(data.element(1).orElseThrow().label()).isEqualTo("[1] textbox 'Search'");
    }

    @Test
    void malformedJsonIsACleanBrowserUseException() {
        assertThatThrownBy(() -> BrowserSnapshot.parse("not json"))
                .isInstanceOf(BrowserUseException.class);
        assertThatThrownBy(() -> BrowserSnapshot.parse("[1,2]"))
                .isInstanceOf(BrowserUseException.class);
        assertThatThrownBy(() -> BrowserSnapshot.parse(null))
                .isInstanceOf(BrowserUseException.class);
    }

    @Test
    void missingOptionalFieldsDefaultSafely() {
        BrowserSnapshot.Data data =
                BrowserSnapshot.parse(
                        "{\"elements\":[{\"i\":1,\"role\":\"link\",\"name\":\"x\"}]}");
        BrowserSnapshot.Element e = data.element(1).orElseThrow();
        assertThat(e.states()).isEmpty();
        assertThat(e.options()).isEmpty();
        assertThat(e.value()).isEmpty();
        assertThat(e.inView()).isTrue();
        assertThat(data.total()).isEqualTo(1);
    }
}
