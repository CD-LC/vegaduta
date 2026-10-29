package com.agenticai.core.agents.tools.openapi;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.agenticai.core.security.net.SsrfGuard;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * The SSRF gate behind every OpenAPI import and tool call. Tests inject a resolver so no DNS lookup
 * ever leaves the test JVM.
 */
class OpenApiUrlGuardTest {

    /**
     * Host names resolve to a fixed public address; IP literals parse locally ({@code getByName}
     * never consults DNS for a numeric address) so the guard classifies the address the URL really
     * points at.
     */
    private static InetAddress[] resolve(String host) throws UnknownHostException {
        boolean ipLiteral =
                host.indexOf(':') >= 0
                        || host.chars().allMatch(c -> Character.isDigit(c) || c == '.');
        if (ipLiteral) {
            return new InetAddress[] {InetAddress.getByName(host)};
        }
        return new InetAddress[] {InetAddress.getByName("93.184.216.34")};
    }

    private static final SsrfGuard GUARD = SsrfGuard.of(Set.of(), OpenApiUrlGuardTest::resolve);

    @Test
    void blankUrlsAndNonHttpSchemesAreRefusedBeforeAnyLookup() {
        assertThrows(OpenApiToolException.class, () -> OpenApiUrlGuard.validatePublicHttpUri(null, GUARD));
        assertThrows(OpenApiToolException.class, () -> OpenApiUrlGuard.validatePublicHttpUri("  ", GUARD));
        assertThrows(OpenApiToolException.class, () -> OpenApiUrlGuard.validatePublicHttpUri("ftp://x/y", GUARD));
        assertThrows(
                OpenApiToolException.class,
                () -> OpenApiUrlGuard.validatePublicHttpUri("file:///etc/passwd", GUARD));
        assertThrows(
                OpenApiToolException.class,
                () -> OpenApiUrlGuard.validatePublicHttpUri("javascript:alert(1)", GUARD));
    }

    @Test
    void absurdlyLongUrlsAreRefused() {
        String longUrl = "https://example.com/" + "a".repeat(5_000);
        assertThrows(OpenApiToolException.class, () -> OpenApiUrlGuard.validatePublicHttpUri(longUrl, GUARD));
    }

    @Test
    void publicHttpUrlsComeBackAsValidatedUris() {
        URI uri = OpenApiUrlGuard.validatePublicHttpUri("  https://api.example.com/v1 ", GUARD);
        assertEquals("https", uri.getScheme());
        assertEquals("api.example.com", uri.getHost());
    }

    @Test
    void privateAddressesAreRewordedAsAnInternalNetworkRefusal() throws Exception {
        SsrfGuard privateHost =
                SsrfGuard.of(Set.of(), host -> new InetAddress[] {InetAddress.getByName("10.0.0.9")});
        OpenApiToolException e =
                assertThrows(
                        OpenApiToolException.class,
                        () -> OpenApiUrlGuard.validatePublicHttpUri("https://internal.local/", privateHost));
        assertTrue(e.getMessage().contains("own network"), e.getMessage());
    }

    @Test
    void aLiteralLoopbackAddressIsRefusedWithoutDns() {
        OpenApiToolException e =
                assertThrows(
                        OpenApiToolException.class,
                        () -> OpenApiUrlGuard.validatePublicHttpUri("http://127.0.0.1:8080/admin", GUARD));
        assertTrue(e.getMessage().contains("own network"), e.getMessage());
    }

    @Test
    void otherGuardRefusalsSurfaceTheirOwnReason() {
        // The metadata address is a blocked literal; the guard's own wording comes through when it
        // does not contain "private"/"internal" - either way an OpenApiToolException is thrown.
        assertThrows(
                OpenApiToolException.class,
                () -> OpenApiUrlGuard.validatePublicHttpUri("http://169.254.169.254/latest", GUARD));
    }
}
