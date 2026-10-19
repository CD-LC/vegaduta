package com.agenticai.core.security.net;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.InetAddress;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

class SafeWebSocketsTest {

    private static SsrfGuard.Target target(String uri, String... addresses) throws Exception {
        URI u = URI.create(uri);
        java.util.ArrayList<InetAddress> list = new java.util.ArrayList<>();
        for (String a : addresses) {
            list.add(InetAddress.getByName(a));
        }
        return new SsrfGuard.Target(u, u.getHost(), u.getPort(), List.copyOf(list), true);
    }

    @Test
    void cleartextWebSocketIsPinnedToTheValidatedAddress() throws Exception {
        SsrfGuard.Target t = target("http://signal.lan:8080", "192.168.1.20");
        URI pinned = SafeWebSockets.pin(t, URI.create("ws://signal.lan:8080/v1/receive/%2B4915?x=1"));

        assertThat(pinned.toString()).isEqualTo("ws://192.168.1.20:8080/v1/receive/%2B4915?x=1");
    }

    @Test
    void ipv6AddressIsBracketed() throws Exception {
        SsrfGuard.Target t = target("http://signal.lan:8080", "fd00::5");
        URI pinned = SafeWebSockets.pin(t, URI.create("ws://signal.lan:8080/v1/receive/a"));

        assertThat(pinned.getHost()).isEqualTo("[fd00:0:0:0:0:0:0:5]");
        assertThat(pinned.getPort()).isEqualTo(8080);
    }

    @Test
    void tlsWebSocketKeepsItsHostNameBecauseTheCertificateBindsIt() throws Exception {
        SsrfGuard.Target t = target("https://signal.example.org", "203.0.113.9");
        URI wss = URI.create("wss://signal.example.org/v1/receive/a");

        assertThat(SafeWebSockets.pin(t, wss)).isEqualTo(wss);
    }

    @Test
    void clientDoesNotFollowRedirectsOrUseAProxy() {
        java.net.http.HttpClient c = SafeWebSockets.client(Duration.ofSeconds(2));
        try {
            assertThat(c.followRedirects()).isEqualTo(java.net.http.HttpClient.Redirect.NEVER);
            assertThat(c.proxy()).isPresent();
        } finally {
            c.shutdownNow();
        }
    }
}
