package com.agenticai.core.agents.tools.browseruse;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class BrowserRouteGuardTest {

    @Test
    void schemeOfIsLowercasedAndNullSafe() {
        assertThat(BrowserRouteGuard.schemeOf("HTTPS://a.example/x")).isEqualTo("https");
        assertThat(BrowserRouteGuard.schemeOf("data:text/plain,hi")).isEqualTo("data");
        assertThat(BrowserRouteGuard.schemeOf("no-scheme")).isEmpty();
        assertThat(BrowserRouteGuard.schemeOf(null)).isEmpty();
    }
}
