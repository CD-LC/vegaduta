package com.agenticai.core.admin.localauth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.agenticai.core.admin.localauth.PasskeyOrigins.Resolved;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class PasskeyOriginsTest {

    @Test
    void anHttpsDomainIsAcceptedAndTheHostBecomesTheRpId() {
        assertThat(new PasskeyOrigins("").resolve("https://Console.Example.test"))
                .isEqualTo(new Resolved("https://console.example.test", "console.example.test"));
        assertThat(new PasskeyOrigins(null).resolve("https://console.example.test:8443"))
                .isEqualTo(new Resolved("https://console.example.test:8443", "console.example.test"));
    }

    @Test
    void theDefaultPortIsDroppedBecauseBrowsersDropIt() {
        assertThat(new PasskeyOrigins("").resolve("https://a.example.test:443").origin()).isEqualTo("https://a.example.test");
    }

    @Test
    void plainHttpIsOnlyAcceptedOnLocalhost() {
        assertThat(new PasskeyOrigins("").resolve("http://localhost:3000")).isEqualTo(new Resolved("http://localhost:3000", "localhost"));
        assertThatThrownBy(() -> new PasskeyOrigins("").resolve("http://console.example.test")).isInstanceOf(LocalAuthException.class);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(
            strings = {
                "https://192.168.1.20",
                "https://192.168.1.20:3000",
                "http://127.0.0.1:3000",
                "https://[::1]",
                "https://[2001:db8::1]:8443",
                "ftp://example.test",
                "https://example.test/path",
                "https://example.test?x=1",
                "https://user@example.test",
                "not an origin",
                "https://",
                "null"
            })
    void ipLiteralsAndMalformedOriginsAreRefusedWithTheDocumentedCode(String origin) {
        assertThatThrownBy(() -> new PasskeyOrigins("").resolve(origin))
                .isInstanceOfSatisfying(
                        LocalAuthException.class,
                        e -> {
                            assertThat(e.status()).isEqualTo(400);
                            assertThat(e.code()).isEqualTo("passkey_origin_unsupported");
                        });
    }

    @Test
    void whenAnAllowListIsConfiguredOnlyThoseOriginsAreAccepted() {
        PasskeyOrigins origins = new PasskeyOrigins(" https://a.example.test , http://localhost:3000, https://b.example.test:8443,not-an-origin");

        assertThat(origins.resolve("https://a.example.test").rpId()).isEqualTo("a.example.test");
        assertThat(origins.resolve("http://localhost:3000").rpId()).isEqualTo("localhost");
        assertThat(origins.resolve("https://b.example.test:8443").rpId()).isEqualTo("b.example.test");
        assertThatThrownBy(() -> origins.resolve("https://c.example.test")).isInstanceOf(LocalAuthException.class);
        assertThatThrownBy(() -> origins.resolve("http://localhost:4000")).isInstanceOf(LocalAuthException.class);
    }
}
