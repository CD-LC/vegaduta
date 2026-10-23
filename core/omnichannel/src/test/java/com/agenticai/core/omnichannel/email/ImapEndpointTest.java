package com.agenticai.core.omnichannel.email;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.agenticai.core.omnichannel.ChannelConfigurationException;
import com.agenticai.core.security.net.SsrfGuard;
import jakarta.mail.Session;
import jakarta.mail.Store;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.UnknownHostException;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The tenant-supplied IMAP host and port are checked by the shared SSRF guard before any socket is
 * opened, and the addresses that passed are the only ones the mail client may connect to.
 */
class ImapEndpointTest {

    /** A resolver that never touches the network: names map to fixed addresses. */
    private static SsrfGuard guard(Set<String> allowedPrivate, Map<String, String> dns) {
        return SsrfGuard.of(
                allowedPrivate,
                host -> {
                    String ip = dns.get(host);
                    if (ip == null) {
                        throw new UnknownHostException(host);
                    }
                    return new InetAddress[] {InetAddress.getByName(ip)};
                });
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "127.0.0.1",
                "localhost",
                "169.254.169.254",
                "10.0.0.5",
                "192.168.1.10",
                "[::1]",
                "::1"
            })
    void internalHostsAreRefusedBeforeAnySocketOpens(String host) {
        ChannelConfigurationException e =
                assertThrows(
                        ChannelConfigurationException.class,
                        () ->
                                ImapEndpoint.apply(
                                        guard(Set.of(), Map.of()),
                                        new Properties(),
                                        "imaps",
                                        host,
                                        993));

        assertTrue(e.getMessage().contains("outbound request policy"), e.getMessage());
        assertTrue(
                e.getMessage().contains("allowed-private-hosts"), "tells the operator the way out");
    }

    @Test
    void aNameThatResolvesToAPrivateAddressIsRefused() {
        SsrfGuard guard = guard(Set.of(), Map.of("mail.attacker.example", "10.0.0.5"));

        assertThrows(
                ChannelConfigurationException.class,
                () ->
                        ImapEndpoint.apply(
                                guard, new Properties(), "imaps", "mail.attacker.example", 993));
    }

    @Test
    void hostsThatAreNotHostNamesAreRefused() {
        SsrfGuard guard = guard(Set.of(), Map.of());
        for (String host :
                new String[] {
                    "evil.example/../x",
                    "a@b.example",
                    "x y.example",
                    "mail.example:25",
                    "",
                    " ",
                    null
                }) {
            assertThrows(
                    ChannelConfigurationException.class,
                    () -> ImapEndpoint.apply(guard, new Properties(), "imaps", host, 993),
                    String.valueOf(host));
        }
    }

    @Test
    void portsOutsideTheValidRangeAreRefused() {
        SsrfGuard guard = guard(Set.of(), Map.of("imap.example.com", "93.184.216.34"));
        for (int port : new int[] {0, -1, 65536}) {
            assertThrows(
                    ChannelConfigurationException.class,
                    () ->
                            ImapEndpoint.apply(
                                    guard, new Properties(), "imaps", "imap.example.com", port));
        }
    }

    @Test
    void aPublicHostIsAllowedAndItsAddressIsPinned() {
        SsrfGuard guard = guard(Set.of(), Map.of("imap.example.com", "93.184.216.34"));
        Properties props = new Properties();

        ImapEndpoint.apply(guard, props, "imaps", "imap.example.com", 993);

        assertInstanceOf(
                ImapEndpoint.PinnedSocketFactory.class, props.get("mail.imaps.socketFactory"));
        assertEquals("false", props.get("mail.imaps.socketFactory.fallback"));
        assertEquals("true", props.get("mail.imaps.ssl.checkserveridentity"));
        assertEquals("imap.example.com", props.get("mail.imaps.host"));
        assertEquals("993", props.get("mail.imaps.port"));
    }

    @Test
    void anOperatorAllowedPrivateHostIsPermittedWithoutAPin() {
        SsrfGuard guard =
                guard(Set.of("bridge.lan.example"), Map.of("bridge.lan.example", "192.168.1.20"));
        Properties props = new Properties();

        ImapEndpoint.apply(guard, props, "imap", "bridge.lan.example", 1143);

        assertFalse(props.containsKey("mail.imap.socketFactory"));
    }

    @Test
    void theClientConnectsToThePinnedAddressEvenIfTheNameResolvesElsewhereOnTheSecondLookup()
            throws Exception {
        // The name below resolves to nothing at all: any connect that goes through ordinary name
        // resolution (what a DNS-rebinding answer would exploit) fails, so a connection reaching
        // the
        // server proves the validated address was used.
        try (ServerSocket server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            AtomicInteger accepted = new AtomicInteger();
            Thread acceptor =
                    new Thread(
                            () -> {
                                try (Socket s = server.accept()) {
                                    accepted.incrementAndGet();
                                    s.getOutputStream().write("* BYE\r\n".getBytes());
                                } catch (IOException ignored) {
                                    // test server
                                }
                            });
            acceptor.start();

            Properties props = new Properties();
            props.put("mail.store.protocol", "imap");
            props.put("mail.imap.connectiontimeout", "3000");
            props.put("mail.imap.timeout", "3000");
            props.put(
                    "mail.imap.socketFactory",
                    new ImapEndpoint.PinnedSocketFactory(
                            List.of(InetAddress.getLoopbackAddress())));
            props.put("mail.imap.socketFactory.fallback", "false");
            try (Store store = Session.getInstance(props).getStore("imap")) {
                assertThrows(
                        Exception.class,
                        () -> store.connect("rebinding.invalid", server.getLocalPort(), "u", "p"));
            }
            acceptor.join(3000);
            assertEquals(1, accepted.get(), "the pinned address must be the one that was dialled");
        }
    }

    @Test
    void theSameHoldsForImaps() throws Exception {
        try (ServerSocket server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            AtomicInteger accepted = new AtomicInteger();
            Thread acceptor =
                    new Thread(
                            () -> {
                                try (Socket s = server.accept()) {
                                    accepted.incrementAndGet();
                                } catch (IOException ignored) {
                                    // test server
                                }
                            });
            acceptor.start();

            Properties props = new Properties();
            props.put("mail.imaps.connectiontimeout", "3000");
            props.put("mail.imaps.timeout", "3000");
            props.put(
                    "mail.imaps.socketFactory",
                    new ImapEndpoint.PinnedSocketFactory(
                            List.of(InetAddress.getLoopbackAddress())));
            props.put("mail.imaps.socketFactory.fallback", "false");
            props.put("mail.imaps.ssl.checkserveridentity", "true");
            try (Store store = Session.getInstance(props).getStore("imaps")) {
                assertThrows(
                        Exception.class,
                        () -> store.connect("rebinding.invalid", server.getLocalPort(), "u", "p"));
            }
            acceptor.join(3000);
            assertEquals(1, accepted.get());
        }
    }

    @Test
    void thePinnedFactoryRefusesAnyConnectThatDoesNotGoThroughThePin() {
        ImapEndpoint.PinnedSocketFactory factory =
                new ImapEndpoint.PinnedSocketFactory(List.of(InetAddress.getLoopbackAddress()));

        assertThrows(IOException.class, () -> factory.createSocket("example.com", 993));
        assertThrows(
                IOException.class,
                () -> factory.createSocket(InetAddress.getLoopbackAddress(), 993));
        assertThrows(
                IllegalArgumentException.class,
                () -> new ImapEndpoint.PinnedSocketFactory(List.of()));
    }

    @Test
    void aClosedPinnedPortFailsToConnectInsteadOfFallingBackToTheName() throws Exception {
        int closedPort;
        try (ServerSocket probe = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            closedPort = probe.getLocalPort();
        }
        ImapEndpoint.PinnedSocketFactory factory =
                new ImapEndpoint.PinnedSocketFactory(List.of(InetAddress.getLoopbackAddress()));
        try (Socket socket = factory.createSocket()) {
            assertThrows(
                    IOException.class,
                    () ->
                            socket.connect(
                                    new InetSocketAddress("rebinding.invalid", closedPort), 1000));
        }
    }
}
