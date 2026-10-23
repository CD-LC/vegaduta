package com.agenticai.core.omnichannel.email;

import com.agenticai.core.omnichannel.ChannelConfigurationException;
import com.agenticai.core.security.net.SsrfBlockedException;
import com.agenticai.core.security.net.SsrfGuard;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketAddress;
import java.util.List;
import java.util.Properties;
import java.util.regex.Pattern;
import javax.net.SocketFactory;

/**
 * Outbound policy for a tenant-supplied IMAP endpoint. The mailbox host and port come from the
 * tenant, so without this a tenant could aim the platform's IMAP client (the connect probe and the
 * five-minute poller) at loopback, the cloud metadata address or any internal service, and read the
 * open/closed answer back from the error text.
 *
 * <p>The host is checked by the shared {@link SsrfGuard} (host-name rules, every resolved address,
 * the operator egress list and {@code app.security.outbound.allowed-private-hosts}). The addresses
 * that passed are then the only ones the mail client may connect to: it is given a {@link
 * SocketFactory} that ignores whatever the host name resolves to a second time, so a DNS answer
 * that changes between the check and the connect (rebinding) never reaches a socket. TLS and
 * hostname verification still run against the original host name. A host the operator allow-listed
 * skips the address checks and connects normally.
 */
final class ImapEndpoint {

    private static final Pattern HOST_NAME = Pattern.compile("[A-Za-z0-9.-]{1,253}");
    private static final Pattern IPV6_LITERAL = Pattern.compile("[0-9A-Fa-f:.]{2,45}");

    private ImapEndpoint() {}

    /**
     * Validates {@code host}:{@code port} and installs the address pin into {@code props} for the
     * given {@code protocol} ("imap" or "imaps").
     *
     * @throws ChannelConfigurationException when the endpoint is refused; the message names the
     *     rule and never carries a connection error
     */
    static void apply(Properties props, String protocol, String host, int port) {
        apply(SsrfGuard.defaultGuard(), props, protocol, host, port);
    }

    static void apply(SsrfGuard guard, Properties props, String protocol, String host, int port) {
        SsrfGuard.Target target = validate(guard, host, port);
        props.put("mail." + protocol + ".host", host);
        props.put("mail." + protocol + ".port", String.valueOf(port));
        if (!target.operatorAllowed()) {
            props.put(
                    "mail." + protocol + ".socketFactory",
                    new PinnedSocketFactory(target.addresses()));
            // Without this the mail client quietly retries with its own unpinned socket whenever
            // the
            // factory throws, which would undo the pin.
            props.put("mail." + protocol + ".socketFactory.fallback", "false");
        }
        props.put("mail." + protocol + ".ssl.checkserveridentity", "true");
    }

    static SsrfGuard.Target validate(SsrfGuard guard, String host, int port) {
        if (host == null || port < 1 || port > 65_535) {
            throw refused("the host or port is not valid");
        }
        String trimmed = host.strip();
        String authority;
        if (HOST_NAME.matcher(trimmed).matches()) {
            authority = trimmed;
        } else if (IPV6_LITERAL.matcher(trimmed).matches() && trimmed.indexOf(':') >= 0) {
            authority = "[" + trimmed + "]";
        } else {
            throw refused("the host name is not valid");
        }
        try {
            return guard.validate("https://" + authority + ":" + port);
        } catch (SsrfBlockedException e) {
            throw refused(e.getMessage());
        }
    }

    private static ChannelConfigurationException refused(String reason) {
        return new ChannelConfigurationException(
                "email",
                "This mailbox host was refused by the outbound request policy ("
                        + reason
                        + "). If your mail server runs on a private network, an operator must list"
                        + " it in app.security.outbound.allowed-private-hosts.");
    }

    /**
     * Connects only to the first address the guard validated (what an ordinary connect by name
     * would also try), whatever host name the client asks for.
     */
    static final class PinnedSocketFactory extends SocketFactory {

        private final InetAddress address;

        PinnedSocketFactory(List<InetAddress> addresses) {
            if (addresses.isEmpty()) {
                throw new IllegalArgumentException("no validated addresses to pin");
            }
            this.address = addresses.get(0);
        }

        /**
         * The mail client creates an unconnected socket and connects it itself; this is the only
         * path it uses.
         */
        @Override
        public Socket createSocket() {
            return new PinnedSocket(address);
        }

        @Override
        public Socket createSocket(String host, int port) throws IOException {
            throw unsupported();
        }

        @Override
        public Socket createSocket(String host, int port, InetAddress localHost, int localPort)
                throws IOException {
            throw unsupported();
        }

        @Override
        public Socket createSocket(InetAddress host, int port) throws IOException {
            throw unsupported();
        }

        @Override
        public Socket createSocket(
                InetAddress host, int port, InetAddress localAddress, int localPort)
                throws IOException {
            throw unsupported();
        }

        private static IOException unsupported() {
            return new IOException("only a pinned, unconnected socket is allowed");
        }
    }

    private static final class PinnedSocket extends Socket {

        private final InetAddress address;

        PinnedSocket(InetAddress address) {
            this.address = address;
        }

        @Override
        public void connect(SocketAddress endpoint, int timeout) throws IOException {
            if (!(endpoint instanceof InetSocketAddress requested)) {
                throw new IOException("unsupported socket address");
            }
            super.connect(new InetSocketAddress(address, requested.getPort()), timeout);
        }
    }
}
