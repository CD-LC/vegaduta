package com.agenticai.core.agents.tools.browseruse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.agenticai.core.security.net.SsrfGuard;
import java.net.InetAddress;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * SEC-009: the post-action page/frame URL check the session manager runs after every browser action
 * (Chromium itself cannot be launched in a unit test).
 */
class BrowserUseFrameUrlCheckTest {

    private static final SsrfGuard GUARD =
            SsrfGuard.of(
                    Set.of(),
                    host -> {
                        Map<String, String> dns =
                                Map.of(
                                        "public.example.com", "93.184.216.34",
                                        "cdn.example.org", "151.101.1.69",
                                        "rebind.attacker.example", "10.0.0.5");
                        String ip = dns.get(host);
                        return ip != null
                                ? new InetAddress[] {InetAddress.getByName(ip)}
                                : InetAddress.getAllByName(host);
                    });

    @Test
    void publicPageWithPublicAndLocalFramesPasses() {
        assertThat(
                        BrowserUseUrlValidator.firstUnsafeUrl(
                                List.of(
                                        "https://public.example.com/article",
                                        "about:blank",
                                        "about:srcdoc",
                                        "data:text/html,<p>x</p>",
                                        "blob:https://public.example.com/5d1c",
                                        "chrome-error://chromewebdata/",
                                        "https://cdn.example.org/embed"),
                                GUARD))
                .isEmpty();
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "http://rebind.attacker.example/", // public-looking name, internal answer
                "http://keycloak:8080/admin", // compose name
                "http://127.0.0.1:9090/metrics",
                "http://169.254.169.254/latest/meta-data/",
                "http://168.63.129.16/machine",
                "http://[::ffff:10.0.0.1]/",
                "file:///etc/passwd",
                "ws://public.example.com/socket",
                "chrome://settings"
            })
    void aFinalUrlInsideTheNetworkIsReported(String internal) {
        assertThat(
                        BrowserUseUrlValidator.firstUnsafeUrl(
                                List.of(
                                        "https://public.example.com/start",
                                        "about:blank",
                                        internal),
                                GUARD))
                .contains(internal);
    }

    @Test
    void mainFrameRedirectedInternallyIsReportedEvenWhenFramesAreFine() {
        // page.url() after Playwright followed a public 302 into the network
        assertThat(
                        BrowserUseUrlValidator.firstUnsafeUrl(
                                List.of(
                                        "http://10.0.0.8:3000/d/admin",
                                        "https://cdn.example.org/embed"),
                                GUARD))
                .contains("http://10.0.0.8:3000/d/admin");
    }

    @Test
    void validatorUsesTheSharedClassification() {
        assertThatThrownBy(
                        () ->
                                BrowserUseUrlValidator.validatePublicHttpUrl(
                                        "http://198.18.0.1/", GUARD))
                .isInstanceOf(BrowserUseException.class)
                .hasMessageContaining("platform's own network");
        assertThatThrownBy(
                        () ->
                                BrowserUseUrlValidator.validatePublicHttpUrl(
                                        "http://rebind.attacker.example/", GUARD))
                .isInstanceOf(BrowserUseException.class)
                .hasMessageNotContaining("10.0.0.5");
        assertThat(
                        BrowserUseUrlValidator.validatePublicHttpUrl(
                                        "https://public.example.com/x", GUARD)
                                .getHost())
                .isEqualTo("public.example.com");
    }
}
