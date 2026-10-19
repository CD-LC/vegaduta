package com.agenticai.core.omnichannel.email;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.omnichannel.ChannelConfigurationException;
import com.agenticai.core.omnichannel.ChannelDeliveryException;
import com.agenticai.core.omnichannel.session.ChannelType;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mail.javamail.JavaMailSender;

/**
 * Same manual-mock convention this module already uses (see {@code
 * WorkflowTriggerRateLimiterTest}'s javadoc). {@link JavaMailSender#createMimeMessage()}
 * is stubbed to return a real {@link MimeMessage} (backed by a {@code null}-
 * properties {@link Session}, the standard way to unit test {@code
 * MimeMessageHelper} content without a live SMTP connection) so the
 * attachment/subject/body assertions below read the message Spring's helper
 * actually built, not a mock's recorded arguments.
 */
class EmailMediaSenderTest {

    private JavaMailSender mailSender;
    @SuppressWarnings("unchecked")
    private final ObjectProvider<JavaMailSender> mailSenderProvider = mock(ObjectProvider.class);

    @BeforeEach
    void setUp() {
        mailSender = mock(JavaMailSender.class);
        when(mailSenderProvider.getIfAvailable()).thenReturn(mailSender);
        when(mailSender.createMimeMessage()).thenAnswer(inv -> new MimeMessage((Session) null));
    }

    @Test
    void channelIsEmail() {
        EmailMediaSender sender = new EmailMediaSender(mailSenderProvider, "smtp.example.com", "workflows@agentic-ai.local");

        assertEquals(ChannelType.EMAIL, sender.channel());
    }

    @Test
    void sendAttachesTheBytesAndUsesTheCaptionAsSubjectAndBody() throws Exception {
        EmailMediaSender sender = new EmailMediaSender(mailSenderProvider, "smtp.example.com", "workflows@agentic-ai.local");
        byte[] bytes = "hello world".getBytes();

        sender.send("recipient@example.com", bytes, "text/plain", "notes.txt", "Here is the file");

        verify(mailSender).send(any(MimeMessage.class));
    }

    @Test
    void sendFallsBackToAGenericSubjectAndBodyWhenNoCaptionIsGiven() throws Exception {
        EmailMediaSender sender = new EmailMediaSender(mailSenderProvider, "smtp.example.com", "workflows@agentic-ai.local");

        sender.send("recipient@example.com", "data".getBytes(), "application/pdf", "report.pdf", null);

        verify(mailSender).send(any(MimeMessage.class));
    }

    @Test
    void sendThrowsChannelConfigurationExceptionWhenNoSmtpHostIsConfigured() {
        EmailMediaSender sender = new EmailMediaSender(mailSenderProvider, "", "workflows@agentic-ai.local");

        assertThrows(ChannelConfigurationException.class,
            () -> sender.send("recipient@example.com", "data".getBytes(), "text/plain", "f.txt", "caption"));
    }

    @Test
    void sendThrowsChannelConfigurationExceptionWhenNoMailSenderBeanIsAvailable() {
        when(mailSenderProvider.getIfAvailable()).thenReturn(null);
        EmailMediaSender sender = new EmailMediaSender(mailSenderProvider, "smtp.example.com", "workflows@agentic-ai.local");

        assertThrows(ChannelConfigurationException.class,
            () -> sender.send("recipient@example.com", "data".getBytes(), "text/plain", "f.txt", "caption"));
    }

    @Test
    void sendWrapsARealDeliveryFailureInAChannelDeliveryException() {
        EmailMediaSender sender = new EmailMediaSender(mailSenderProvider, "smtp.example.com", "workflows@agentic-ai.local");
        org.mockito.Mockito.doThrow(new org.springframework.mail.MailSendException("boom"))
            .when(mailSender).send(any(MimeMessage.class));

        ChannelDeliveryException ex = assertThrows(ChannelDeliveryException.class,
            () -> sender.send("recipient@example.com", "data".getBytes(), "text/plain", "f.txt", "caption"));
        assertTrue(ex.getMessage().contains("boom"));
    }
}
