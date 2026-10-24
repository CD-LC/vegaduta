package com.agenticai.core.omnichannel.email;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.agenticai.core.crypto.EncryptionService;
import com.agenticai.core.omnichannel.ChannelConfigurationException;
import com.agenticai.core.omnichannel.email.dto.EmailActivationRequest;
import com.agenticai.core.security.net.SsrfGuard;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/**
 * Connecting a tenant's IMAP mailbox must not let the tenant aim the platform at an internal
 * service, and must not echo what answered back to the caller.
 */
class EmailCredentialServiceTest {

    private final EmailCredentialRepository repository = mock(EmailCredentialRepository.class);
    private final EncryptionService encryption = mock(EncryptionService.class);

    private EmailCredentialService service(SsrfGuard guard) {
        return new EmailCredentialService(repository, encryption, () -> guard);
    }

    @Test
    void anInternalHostIsRefusedAndNothingIsEverDialled() throws Exception {
        try (ServerSocket listener = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            listener.setSoTimeout(500);
            EmailActivationRequest request =
                    new EmailActivationRequest(
                            "127.0.0.1", listener.getLocalPort(), "user", "pw", false);

            ChannelConfigurationException refused =
                    assertThrows(
                            ChannelConfigurationException.class,
                            () ->
                                    service(SsrfGuard.of(Set.of(), InetAddress::getAllByName))
                                            .activate(request));

            assertTrue(
                    refused.getMessage().contains("outbound request policy"), refused.getMessage());
            assertThrows(
                    SocketTimeoutException.class, listener::accept, "no connection may be opened");
            verify(repository, never()).save(any());
        }
    }

    @Test
    void theConnectionErrorTextIsNotReturnedToTheCaller() throws Exception {
        try (ServerSocket server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            AtomicBoolean answered = new AtomicBoolean();
            Thread fake =
                    new Thread(
                            () -> {
                                try (Socket s = server.accept()) {
                                    answered.set(true);
                                    s.getOutputStream()
                                            .write("* BYE internal-banner-xyz\r\n".getBytes());
                                } catch (IOException ignored) {
                                    // test server
                                }
                            });
            fake.start();
            // The operator allow-listed this host, so the guard lets it through.
            SsrfGuard allowed =
                    SsrfGuard.of(
                            Set.of("127.0.0.1:" + server.getLocalPort()),
                            InetAddress::getAllByName);
            EmailActivationRequest request =
                    new EmailActivationRequest(
                            "127.0.0.1", server.getLocalPort(), "user", "pw", false);

            Logger serviceLog = (Logger) LoggerFactory.getLogger(EmailCredentialService.class);
            ListAppender<ILoggingEvent> appender = new ListAppender<>();
            appender.start();
            serviceLog.addAppender(appender);
            ChannelConfigurationException failure;
            try {
                failure =
                        assertThrows(
                                ChannelConfigurationException.class,
                                () -> service(allowed).activate(request));
            } finally {
                serviceLog.detachAppender(appender);
            }

            fake.join(3000);
            // The server log gets a fixed text and the exception class, never the mail client's
            // message (banner, host and port) or a stack trace carrying it.
            assertEquals(1, appender.list.size());
            ILoggingEvent probe = appender.list.get(0);
            assertTrue(
                    probe.getFormattedMessage().startsWith("IMAP connect probe failed for"),
                    probe.getFormattedMessage());
            assertFalse(
                    probe.getFormattedMessage().contains("internal-banner-xyz"),
                    probe.getFormattedMessage());
            assertFalse(
                    probe.getFormattedMessage().contains("127.0.0.1"), probe.getFormattedMessage());
            assertEquals(null, probe.getThrowableProxy(), "no exception text or stack trace");
            assertTrue(answered.get(), "the allow-listed host really was dialled");
            assertFalse(failure.getMessage().contains("internal-banner-xyz"), failure.getMessage());
            assertFalse(failure.getMessage().contains("BYE"), failure.getMessage());
            assertFalse(failure.getMessage().contains("127.0.0.1"), failure.getMessage());
            assertTrue(failure.getMessage().startsWith("Could not connect to this IMAP mailbox"));
            verify(repository, never()).save(any());
        }
    }
}
