package com.agenticai.core.omnichannel.email;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.agenticai.core.omnichannel.email.EmailReplyClassifier.Headers;
import com.agenticai.core.omnichannel.email.EmailReplyClassifier.Kind;
import java.util.List;
import org.junit.jupiter.api.Test;

class EmailReplyClassifierTest {

    private static Headers h(String autoSubmitted, String xAutoreply, String precedence, String contentType,
                             String subject, String from, String returnPath) {
        return new Headers(null, null, autoSubmitted, xAutoreply, precedence, contentType, subject, from, returnPath);
    }

    @Test
    void parsesInReplyToThenReferencesStrippingBracketsAndDeduplicating() {
        List<String> ids = EmailReplyClassifier.referencedMessageIds(
            "<a@x.test>", "<z@x.test>\n <a@x.test> <b@x.test>");
        assertEquals(List.of("a@x.test", "z@x.test", "b@x.test"), ids);
    }

    @Test
    void toleratesBareIdAndBlankHeaders() {
        assertEquals(List.of("bare@x.test"), EmailReplyClassifier.referencedMessageIds("bare@x.test", null));
        assertTrue(EmailReplyClassifier.referencedMessageIds(null, "  ").isEmpty());
    }

    @Test
    void normalizeMessageIdStripsBrackets() {
        assertEquals("a@x.test", EmailReplyClassifier.normalizeMessageId(" <a@x.test> "));
        assertEquals("a@x.test", EmailReplyClassifier.normalizeMessageId("a@x.test"));
    }

    @Test
    void plainHumanMailIsAReply() {
        assertEquals(Kind.REPLY, EmailReplyClassifier.classify(h(null, null, null, "text/plain", "Re: Hello", "ann@x.test", null)));
        assertEquals(Kind.REPLY, EmailReplyClassifier.classify(h("no", null, null, "text/plain", "Re: Hello", "ann@x.test", null)));
    }

    @Test
    void dsnContentTypeAndMailerDaemonAreBounces() {
        assertEquals(Kind.BOUNCE, EmailReplyClassifier.classify(h(null, null, null,
            "multipart/report; report-type=delivery-status; boundary=x", "Anything", "ann@x.test", null)));
        assertEquals(Kind.BOUNCE, EmailReplyClassifier.classify(h(null, null, null,
            "text/plain", "Undeliverable: Hello", "MAILER-DAEMON@mx.x.test", "<>")));
        assertEquals(Kind.BOUNCE, EmailReplyClassifier.classify(h(null, null, null,
            "text/plain", "Delivery Status Notification (Failure)", "postmaster@x.test", null)));
    }

    @Test
    void autoSubmittedXAutoreplyPrecedenceAndSubjectAreAutoReplies() {
        assertEquals(Kind.AUTO_REPLY, EmailReplyClassifier.classify(h("auto-replied", null, null, "text/plain", "Re: Hi", "a@x.test", null)));
        assertEquals(Kind.AUTO_REPLY, EmailReplyClassifier.classify(h(null, "yes", null, "text/plain", "Re: Hi", "a@x.test", null)));
        assertEquals(Kind.AUTO_REPLY, EmailReplyClassifier.classify(h(null, null, "auto_reply", "text/plain", "Re: Hi", "a@x.test", null)));
        assertEquals(Kind.AUTO_REPLY, EmailReplyClassifier.classify(h(null, null, null, "text/plain", "Automatic reply: Hi", "a@x.test", null)));
        assertEquals(Kind.AUTO_REPLY, EmailReplyClassifier.classify(h(null, null, null, "text/plain", "Out of Office", "a@x.test", null)));
    }

    @Test
    void bounceOutranksAutoReply() {
        assertEquals(Kind.BOUNCE, EmailReplyClassifier.classify(h("auto-generated", null, "bulk",
            "multipart/report; report-type=delivery-status", "Undeliverable", "mailer-daemon@x.test", "<>")));
    }

    @Test
    void onlyFiveXxStatusesAreTreatedAsPermanentBounces() {
        assertTrue(EmailReplyClassifier.isPermanentBounce("Status: 5.1.1\nDiagnostic-Code: smtp; 550 no such user"));
        assertFalse(EmailReplyClassifier.isPermanentBounce("Status: 4.2.2 mailbox full"));
        assertFalse(EmailReplyClassifier.isPermanentBounce("Your message could not be delivered"));
        assertFalse(EmailReplyClassifier.isPermanentBounce(null));
    }
}
