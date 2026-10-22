package com.agenticai.core.security.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.http.ContentDisposition;

/**
 * Prod signed-in pass 2026-10-05, bug 3: a workflow export was saved as
 * {@code =?UTF-8?Q?e2e-workflow.vegaduta-workflow.json?=} because the header
 * carried an RFC 2047 encoded-word in {@code filename=}.
 */
class AttachmentDispositionTest {

    @Test
    void aPlainAsciiNameIsOnePlainQuotedFilenameWithNoEncodedWord() {
        String header = AttachmentDisposition.attachment("e2e-workflow.vegaduta-workflow.json");

        assertEquals("attachment; filename=\"e2e-workflow.vegaduta-workflow.json\"", header);
        assertFalse(header.contains("=?"), header);
    }

    /**
     * Spring 6 sent an RFC 2047 encoded word in {@code filename=} for this call, which is the bug above.
     * Spring 7 (Boot 4) writes a plain quoted name plus {@code filename*=}, the same shape as this class, so the
     * framework no longer has the defect. The assertion keeps the guard: the encoded word must not come back.
     */
    @Test
    void springsCharsetBuilderNoLongerEmitsTheEncodedWordThisClassReplaced() {
        String spring = ContentDisposition.attachment()
            .filename("e2e-workflow.vegaduta-workflow.json", StandardCharsets.UTF_8).build().toString();

        assertFalse(spring.contains("=?UTF-8?Q?"), spring);
    }

    @Test
    void aNonAsciiNameKeepsAnAsciiFallbackAndAddsTheRfc5987Form() {
        String header = AttachmentDisposition.attachment("VegaDūta café report.json");

        assertEquals("attachment; filename=\"VegaD_ta caf_ report.json\"; "
            + "filename*=UTF-8''VegaD%C5%ABta%20caf%C3%A9%20report.json", header);
        String encoded = header.substring(header.indexOf("UTF-8''") + "UTF-8''".length());
        assertEquals("VegaDūta café report.json", URLDecoder.decode(encoded.replace("+", "%2B"), StandardCharsets.UTF_8));
        // Any standards-following client reads the same name back.
        assertEquals("VegaDūta café report.json", ContentDisposition.parse(header).getFilename());
    }

    @Test
    void quotesBackslashesPathsAndControlCharactersNeverReachTheHeader() {
        assertEquals("attachment; filename=\"passwd\"", AttachmentDisposition.attachment("../../etc/passwd"));
        assertEquals("attachment; filename=\"evil.md\"", AttachmentDisposition.attachment("..\\evil.md"));
        assertEquals("attachment; filename=\"a_b.txt\"; filename*=UTF-8''a%22b.txt",
            AttachmentDisposition.attachment("a\"b.txt"));
        assertEquals("attachment; filename=\"ab.txt\"", AttachmentDisposition.attachment("a\r\nb.txt"));
        assertEquals("attachment; filename=\"download\"", AttachmentDisposition.attachment(null));
        assertEquals("attachment; filename=\"download\"", AttachmentDisposition.attachment("  "));
        assertEquals("attachment; filename=\"download\"", AttachmentDisposition.attachment(".."));
    }

    @Test
    void inlineUsesTheSameNameRules() {
        assertEquals("inline; filename=\"chart.png\"", AttachmentDisposition.inline("chart.png"));
    }
}
