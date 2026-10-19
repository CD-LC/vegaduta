package com.agenticai.core.agents.tools.browseruse;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * The abort latch's "same page" rule: navigating back to the page the task was aborted on must not
 * clear it.
 */
class BrowserV2StateTest {

    private static BrowserV2State aborted(String url) {
        BrowserV2State state = new BrowserV2State(2, 3);
        state.detector().record("click|button|Go", false);
        state.detector().record("click|button|Go", false);
        state.detector().record("click|button|Go", false);
        assertThat(state.isAborted()).isTrue();
        state.noteAbortUrl(url);
        return state;
    }

    @Test
    void navigatingToTheAbortedPageIsRecognisedIgnoringFragmentAndTrailingSlash() {
        BrowserV2State state = aborted("https://shop.example/cart/");
        assertThat(state.isAbortedPage("https://shop.example/cart")).isTrue();
        assertThat(state.isAbortedPage("https://SHOP.example/cart#top")).isTrue();
    }

    @Test
    void aDifferentPageIsAReplanAndClearsTheLatch() {
        BrowserV2State state = aborted("https://shop.example/cart");
        assertThat(state.isAbortedPage("https://shop.example/checkout")).isFalse();
        assertThat(state.isAbortedPage("https://shop.example/cart?x=1")).isFalse();
        state.resetForReplan();
        assertThat(state.isAborted()).isFalse();
        assertThat(state.isAbortedPage("https://shop.example/cart")).isFalse();
    }

    @Test
    void anUnknownAbortUrlNeverBlocksANavigation() {
        BrowserV2State state = aborted("");
        assertThat(state.isAbortedPage("https://shop.example/cart")).isFalse();
        BrowserV2State fresh = new BrowserV2State(4, 8);
        assertThat(fresh.isAbortedPage("https://shop.example/cart")).isFalse();
    }
}
