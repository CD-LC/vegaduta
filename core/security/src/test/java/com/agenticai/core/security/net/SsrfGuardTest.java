package com.agenticai.core.security.net;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.core5.http.io.entity.EntityUtils;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class SsrfGuardTest {

    /** Maps names to fixed addresses; anything else is "unknown". */
    private static SsrfGuard.HostResolver fakeDns(Map<String, String> table) {
        return host -> {
            String ip = table.get(host);
            if (ip == null) {
                // Literals still parse locally, exactly like the system resolver.
                return InetAddress.getAllByName(host);
            }
            return new InetAddress[] {InetAddress.getByName(ip)};
        };
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "127.0.0.1", "10.1.2.3", "172.16.0.1", "192.168.1.1", "169.254.169.254", "168.63.129.16",
        "100.64.0.1", "0.0.0.0", "198.18.0.1", "224.0.0.1", "240.0.0.1", "::1", "::", "fe80::1",
        "fc00::1", "fd00::1", "fec0::1", "::ffff:127.0.0.1", "::ffff:a9fe:a9fe", "::ffff:10.0.0.1",
        "::10.0.0.1", "2002:0a00:0001::1", "2002:7f00:0001::", "2001:0:4136:e378::1",
        "64:ff9b::a9fe:a9fe", "2001:db8::1"
    })
    void internalAndReservedAddressesAreBlocked(String literal) throws Exception {
        InetAddress address = InetAddress.getByName(literal);
        if (address instanceof java.net.Inet4Address && literal.contains(":")) {
            // The JDK already unwrapped an IPv4-mapped literal; classify the raw 16 bytes too.
            assertThat(SsrfGuard.isBlockedAddress(address)).as(literal).isTrue();
            return;
        }
        assertThat(SsrfGuard.isBlockedAddress(address)).as(literal).isTrue();
    }

    @Test
    void ipv4MappedAddressGivenAsRawIpv6BytesIsDecodedAndBlocked() throws Exception {
        byte[] mapped = new byte[16];
        mapped[10] = (byte) 0xff;
        mapped[11] = (byte) 0xff;
        mapped[12] = (byte) 169;
        mapped[13] = (byte) 254;
        mapped[14] = (byte) 169;
        mapped[15] = (byte) 254;
        InetAddress v6 = java.net.Inet6Address.getByAddress(null, mapped, -1);
        assertThat(SsrfGuard.isBlockedAddress(v6)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"8.8.8.8", "1.1.1.1", "2606:4700:4700::1111", "151.101.1.69"})
    void publicAddressesPass(String literal) throws Exception {
        assertThat(SsrfGuard.isBlockedAddress(InetAddress.getByName(literal))).as(literal).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "http://127.0.0.1/", "http://[::1]:8080/", "http://[::ffff:127.0.0.1]/", "http://169.254.169.254/latest",
        "http://168.63.129.16/machine", "http://2130706433/", "http://0/", "http://localhost:8080/",
        "http://core:8080/actuator", "http://keycloak:8080/", "http://metadata.google.internal/",
        "http://api.svc.cluster.local/", "http://printer.local/", "http://foo.localhost/",
        "file:///etc/passwd", "gopher://evil.example/", "ftp://example.com/",
        "http://user:pw@example.com/", "http://example.com@127.0.0.1/"
    })
    void dangerousUrlsAreRefused(String url) {
        SsrfGuard guard = SsrfGuard.of(Set.of(), fakeDns(Map.of("example.com", "93.184.216.34")));
        assertThatThrownBy(() -> guard.validate(url)).as(url).isInstanceOf(SsrfBlockedException.class);
    }

    @Test
    void hostNameResolvingToAPrivateAddressIsRefused() {
        SsrfGuard guard = SsrfGuard.of(Set.of(), fakeDns(Map.of("rebind.attacker.example", "10.0.0.5")));
        assertThatThrownBy(() -> guard.validate("https://rebind.attacker.example/x"))
            .isInstanceOf(SsrfBlockedException.class)
            .hasMessageNotContaining("10.0.0.5");
    }

    @Test
    void anyPrivateAddressAmongSeveralRecordsRefusesTheHost() {
        SsrfGuard.HostResolver resolver = host -> new InetAddress[] {
            InetAddress.getByName("93.184.216.34"), InetAddress.getByName("127.0.0.1")};
        SsrfGuard guard = SsrfGuard.of(Set.of(), resolver);
        assertThatThrownBy(() -> guard.validate("https://mixed.example.com/"))
            .isInstanceOf(SsrfBlockedException.class);
    }

    @Test
    void publicUrlPassesAndCarriesTheValidatedAddresses() {
        SsrfGuard guard = SsrfGuard.of(Set.of(), fakeDns(Map.of("example.com", "93.184.216.34")));
        SsrfGuard.Target target = guard.validate("https://Example.COM./path");
        assertThat(target.host()).isEqualTo("example.com");
        assertThat(target.port()).isEqualTo(443);
        assertThat(target.addresses()).extracting(InetAddress::getHostAddress).containsExactly("93.184.216.34");
    }

    @Test
    void redirectsAreRevalidatedIncludingSchemeRelativeHostSwitches() {
        SsrfGuard guard = SsrfGuard.of(Set.of(), fakeDns(Map.of(
            "example.com", "93.184.216.34", "other.example.org", "93.184.216.35")));
        URI from = URI.create("https://example.com/start");
        assertThat(guard.resolveRedirect(from, "/next").uri().toString()).isEqualTo("https://example.com/next");
        assertThat(guard.resolveRedirect(from, "//other.example.org/x").host()).isEqualTo("other.example.org");
        assertThatThrownBy(() -> guard.resolveRedirect(from, "//core:8080/actuator"))
            .isInstanceOf(SsrfBlockedException.class);
        assertThatThrownBy(() -> guard.resolveRedirect(from, "http://169.254.169.254/"))
            .isInstanceOf(SsrfBlockedException.class);
        assertThatThrownBy(() -> guard.resolveRedirect(from, "file:///etc/passwd"))
            .isInstanceOf(SsrfBlockedException.class);
        assertThatThrownBy(() -> guard.resolveRedirect(from, null))
            .isInstanceOf(SsrfBlockedException.class);
    }

    @Test
    void operatorAllowListIsTheOnlyWayToReachAPrivateHost() {
        SsrfGuard guard = SsrfGuard.of(Set.of("ollama:11434"), fakeDns(Map.of("ollama", "172.18.0.4")));
        assertThat(guard.validate("http://ollama:11434/api").operatorAllowed()).isTrue();
        assertThatThrownBy(() -> guard.validate("http://ollama:22/")).isInstanceOf(SsrfBlockedException.class);
    }

    @Test
    void sameOriginComparesSchemeHostAndEffectivePort() {
        assertThat(SsrfGuard.sameOrigin(URI.create("https://a.example/x"), URI.create("https://A.example:443/y"))).isTrue();
        assertThat(SsrfGuard.sameOrigin(URI.create("https://a.example/x"), URI.create("http://a.example/y"))).isFalse();
        assertThat(SsrfGuard.sameOrigin(URI.create("https://a.example/x"), URI.create("https://b.example/x"))).isFalse();
    }

    @Test
    void dnsResolverRefusesPrivateAnswersAtConnectTime() {
        SsrfGuard guard = SsrfGuard.of(Set.of(), fakeDns(Map.of("rebind.attacker.example", "127.0.0.1")));
        assertThatThrownBy(() -> guard.dnsResolver().resolve("rebind.attacker.example"))
            .isInstanceOf(SsrfGuard.BlockedAddressException.class)
            .isInstanceOf(UnknownHostException.class);
    }

    @Test
    void dnsResolverIsConsultedOncePerConnectionAndItsAnswerIsWhatGetsUsed() throws Exception {
        // A rebinding resolver: first answer public, every later answer loopback.
        AtomicInteger calls = new AtomicInteger();
        SsrfGuard.HostResolver rebinding = host -> calls.getAndIncrement() == 0
            ? new InetAddress[] {InetAddress.getByName("93.184.216.34")}
            : new InetAddress[] {InetAddress.getByName("127.0.0.1")};
        SsrfGuard guard = SsrfGuard.of(Set.of(), rebinding);
        assertThat(guard.dnsResolver().resolve("rebind.attacker.example")[0].getHostAddress())
            .isEqualTo("93.184.216.34");
        assertThatThrownBy(() -> guard.dnsResolver().resolve("rebind.attacker.example"))
            .isInstanceOf(SsrfGuard.BlockedAddressException.class);
    }

    @Test
    void guardedClientCannotReachALoopbackServerThroughAPublicLookingName() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", exchange -> {
            byte[] body = "internal-secret".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        int port = server.getAddress().getPort();
        try {
            SsrfGuard blocked = SsrfGuard.of(Set.of(), fakeDns(Map.of("innocent.example.com", "127.0.0.1")));
            try (CloseableHttpClient client = SafeHttpClients.create(
                    blocked, Duration.ofSeconds(2), Duration.ofSeconds(2), true)) {
                assertThatThrownBy(() -> client.execute(
                        new HttpGet("http://innocent.example.com:" + port + "/"), r -> EntityUtils.toString(r.getEntity())))
                    .isInstanceOf(UnknownHostException.class);
                assertThatThrownBy(() -> client.execute(
                        new HttpGet("http://127.0.0.1:" + port + "/"), r -> EntityUtils.toString(r.getEntity())))
                    .isInstanceOf(UnknownHostException.class);
            }
            // Same server, operator allow-listed: reachable. Proves the guard's resolver is what connects.
            SsrfGuard allowed = SsrfGuard.of(Set.of("innocent.example.com"),
                fakeDns(Map.of("innocent.example.com", "127.0.0.1")));
            try (CloseableHttpClient client = SafeHttpClients.create(
                    allowed, Duration.ofSeconds(2), Duration.ofSeconds(2), false)) {
                String body = client.execute(new HttpGet("http://innocent.example.com:" + port + "/"),
                    r -> EntityUtils.toString(r.getEntity()));
                assertThat(body).isEqualTo("internal-secret");
            }
        } finally {
            server.stop(0);
        }
    }

    @Test
    void guardedClientRevalidatesAutomaticRedirectsIntoThePrivateNetwork() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        int[] port = new int[1];
        server.createContext("/", exchange -> {
            // "public" host (allow-listed for the test) redirects to a loopback-resolving name.
            exchange.getResponseHeaders().add("Location", "http://internal.example.net:" + port[0] + "/secret");
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });
        server.start();
        port[0] = server.getAddress().getPort();
        try {
            SsrfGuard guard = SsrfGuard.of(Set.of("public.example.com"), fakeDns(Map.of(
                "public.example.com", "127.0.0.1", "internal.example.net", "127.0.0.1")));
            try (CloseableHttpClient client = SafeHttpClients.create(
                    guard, Duration.ofSeconds(2), Duration.ofSeconds(2), true)) {
                assertThatThrownBy(() -> client.execute(
                        new HttpGet("http://public.example.com:" + port[0] + "/"), r -> r.getCode()))
                    .isInstanceOf(UnknownHostException.class);
            }
        } finally {
            server.stop(0);
        }
    }
}
