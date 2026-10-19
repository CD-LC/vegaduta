package com.agenticai.core.platform;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeMultipart;
import java.util.List;
import java.util.Properties;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.mail.javamail.JavaMailSender;

class NewsletterServiceTest {

    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final JavaMailSender mailSender = mock(JavaMailSender.class);

    @SuppressWarnings("unchecked")
    private final ObjectProvider<JavaMailSender> provider = mock(ObjectProvider.class);

    private final NewsletterService service =
            new NewsletterService(jdbc, provider, "news@vegaduta.ai", "", "contact@vegaduta.ai");

    @BeforeEach
    void wireMailSender() {
        when(provider.getIfAvailable()).thenReturn(mailSender);
        when(mailSender.createMimeMessage())
                .thenAnswer(inv -> new MimeMessage(Session.getInstance(new Properties())));
    }

    private MimeMessage sendAndCapture(String bodyHtml, String bodyText) throws Exception {
        service.sendOne(mailSender, "admin@acme.test", "What's new", bodyHtml, bodyText);
        ArgumentCaptor<MimeMessage> captor = ArgumentCaptor.forClass(MimeMessage.class);
        verify(mailSender).send(captor.capture());
        MimeMessage message = captor.getValue();
        message.saveChanges(); // what the real transport does before writing - materialises
        // Content-Type
        return message;
    }

    @Test
    void sendOneIsMultipartAlternativeWithPlainFirstAndHtmlLast() throws Exception {
        MimeMessage message =
                sendAndCapture(
                        "<p>Hello <strong>there</strong></p><p>Read the <a"
                                + " href=\"https://vegaduta.ai/changelog\">changelog</a>.</p>",
                        null);

        assertTrue(
                message.getContentType().toLowerCase().startsWith("multipart/alternative"),
                "root content type was " + message.getContentType());
        MimeMultipart body = (MimeMultipart) message.getContent();
        assertEquals(2, body.getCount());
        assertTrue(body.getBodyPart(0).isMimeType("text/plain"), "first part must be text/plain");
        assertTrue(body.getBodyPart(1).isMimeType("text/html"), "last part must be text/html");

        String plain = (String) body.getBodyPart(0).getContent();
        assertEquals(
                "Hello there\n\nRead the changelog (https://vegaduta.ai/changelog).",
                plain.split("\n\n--\n")[0]);
        assertTrue(
                plain.contains("opted in to VegaDūta product news"),
                "plain footer mirrors the HTML footer");

        String html = (String) body.getBodyPart(1).getContent();
        assertTrue(html.contains("<strong>there</strong>"));
        assertTrue(html.contains("<!DOCTYPE html>"), "HTML part is the branded wrapper");

        assertEquals("news@vegaduta.ai", message.getFrom()[0].toString());
        assertEquals("admin@acme.test", message.getAllRecipients()[0].toString());
        assertEquals("What's new", message.getSubject());
    }

    @Test
    void sendOneCarriesListUnsubscribeHeaders() throws Exception {
        MimeMessage message = sendAndCapture("<p>Hi</p>", null);

        String[] unsubscribe = message.getHeader("List-Unsubscribe");
        assertNotNull(unsubscribe);
        assertEquals(1, unsubscribe.length);
        assertEquals("<mailto:contact@vegaduta.ai?subject=unsubscribe>", unsubscribe[0]);

        String[] post = message.getHeader("List-Unsubscribe-Post");
        assertNotNull(post);
        assertEquals(1, post.length);
        assertEquals("List-Unsubscribe=One-Click", post[0]);
    }

    @Test
    void unsubscribeMailtoIsConfigurable() throws Exception {
        NewsletterService custom =
                new NewsletterService(jdbc, provider, "news@vegaduta.ai", "", "unsub@example.test");
        custom.sendOne(mailSender, "admin@acme.test", "s", "<p>Hi</p>", null);
        ArgumentCaptor<MimeMessage> captor = ArgumentCaptor.forClass(MimeMessage.class);
        verify(mailSender).send(captor.capture());
        assertEquals(
                "<mailto:unsub@example.test?subject=unsubscribe>",
                captor.getValue().getHeader("List-Unsubscribe")[0]);
    }

    @Test
    void adminSuppliedBodyTextIsUsedVerbatim() throws Exception {
        MimeMessage message =
                sendAndCapture("<p>Hello <b>HTML</b></p>", "Hello plain, hand-written.");
        MimeMultipart body = (MimeMultipart) message.getContent();
        assertEquals("Hello plain, hand-written.", body.getBodyPart(0).getContent());
    }

    @Test
    void blankBodyTextFallsBackToDerivation() throws Exception {
        MimeMessage message = sendAndCapture("<p>Hello <b>HTML</b></p>", "   ");
        MimeMultipart body = (MimeMultipart) message.getContent();
        assertTrue(((String) body.getBodyPart(0).getContent()).startsWith("Hello HTML"));
    }

    @Test
    void fullHtmlDocumentGetsNoExtraFooterInEitherPart() throws Exception {
        String doc =
                "<html><head><title>x</title><style>p{color:red}</style></head><body><p>Doc"
                        + " body</p></body></html>";
        MimeMessage message = sendAndCapture(doc, null);
        MimeMultipart body = (MimeMultipart) message.getContent();
        assertEquals("Doc body", body.getBodyPart(0).getContent());
        assertEquals(doc, body.getBodyPart(1).getContent());
    }

    @Test
    void sendRoutesThroughMultipartPathAndCountsSent() throws Exception {
        UUID optedIn = UUID.randomUUID();
        UUID optedOut = UUID.randomUUID();
        when(jdbc.query(anyString(), any(RowMapper.class)))
                .thenReturn(
                        List.of(
                                new NewsletterRecipient(optedIn, "Acme", "admin@acme.test", true),
                                new NewsletterRecipient(
                                        optedOut, "Beta", "admin@beta.test", false)));

        NewsletterSendResult result =
                service.send(
                        new NewsletterSendRequest("Subject", "<p>Body</p>", null, null),
                        "platform-admin");

        assertEquals(1, result.sent());
        assertEquals(1, result.skipped());
        ArgumentCaptor<MimeMessage> captor = ArgumentCaptor.forClass(MimeMessage.class);
        verify(mailSender).send(captor.capture());
        MimeMessage message = captor.getValue();
        message.saveChanges();
        assertTrue(message.getContentType().toLowerCase().startsWith("multipart/alternative"));
        assertNotNull(message.getHeader("List-Unsubscribe"));
        assertNotNull(message.getHeader("List-Unsubscribe-Post"));
    }

    @Test
    void htmlToPlainTextStripsDecodesAndKeepsLinks() {
        String html =
                """
<!-- c -->
<h1>Title &amp; more</h1>
<p>Line one<br>line two&nbsp;&#8212;&#x2019;ok&#39;</p>
<ul><li>First</li><li><a href="https://vegaduta.ai">https://vegaduta.ai</a></li></ul>
<p>See <a href='https://vegaduta.ai/docs'><em>the docs</em></a> and <a href="#top">top</a>.</p>
""";
        String text = NewsletterService.htmlToPlainText(html);
        assertEquals(
                "Title & more\n\nLine one\nline two —’ok'\n\n- First\n- https://vegaduta.ai\n\n"
                        + "See the docs (https://vegaduta.ai/docs) and top.",
                text);
        assertFalse(text.contains("<"));
    }

    @Test
    void htmlToPlainTextHandlesNullAndEmpty() {
        assertEquals("", NewsletterService.htmlToPlainText(null));
        assertEquals("", NewsletterService.htmlToPlainText("  "));
    }
}
