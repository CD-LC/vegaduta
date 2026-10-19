package com.agenticai.core.knowledge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;

class InProcessTextExtractorTest {

    private static byte[] zip(String entryName, String content) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            zip.putNextEntry(new ZipEntry(entryName));
            zip.write(content.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        return bytes.toByteArray();
    }

    @Test
    void htmlDropsScriptsStylesAndTagsAndDecodesEntities() {
        String html = "<html><head><title>x</title><style>p{}</style></head><body><h1>Refunds</h1>"
            + "<script>alert(1)</script><p>Paid &amp; settled &#8212; in 14&nbsp;days.</p><p>Second<br/>line</p></body></html>";

        String text = InProcessTextExtractor.extract("policy.HTML", html.getBytes(StandardCharsets.UTF_8));

        assertThat(text).contains("Refunds").contains("Paid & settled — in 14 days.").contains("Second\nline");
        assertThat(text).doesNotContain("alert").doesNotContain("<").doesNotContain("title");
    }

    @Test
    void docxKeepsParagraphsAndTabsAndDecodesEntities() throws IOException {
        String xml = "<w:document><w:body>"
            + "<w:p><w:r><w:t>Hello &lt;team&gt;</w:t></w:r></w:p>"
            + "<w:p><w:r><w:t xml:space=\"preserve\">A</w:t></w:r><w:r><w:tab/></w:r><w:r><w:t>B</w:t></w:r></w:p>"
            + "</w:body></w:document>";

        String text = InProcessTextExtractor.extract("notes.docx", zip("word/document.xml", xml));

        assertThat(text).isEqualTo("Hello <team>\nA B");
    }

    @Test
    void docxWithoutADocumentPartOrNotAZipIsAClientError() throws IOException {
        assertThatThrownBy(() -> InProcessTextExtractor.extract("a.docx", zip("other.xml", "<x/>")))
            .isInstanceOf(InvalidKnowledgeUploadException.class);
        assertThatThrownBy(() -> InProcessTextExtractor.extract("a.docx", "not a zip".getBytes(StandardCharsets.UTF_8)))
            .isInstanceOf(InvalidKnowledgeUploadException.class);
    }

    @Test
    void anInflatedDocumentOverTheCapIsRefused() throws IOException {
        byte[] bomb = zip("word/document.xml", "a".repeat(InProcessTextExtractor.MAX_INFLATED_BYTES + 1));
        assertThat(bomb.length).isLessThan(InProcessTextExtractor.MAX_INFLATED_BYTES / 100);
        assertThatThrownBy(() -> InProcessTextExtractor.extract("a.docx", bomb))
            .isInstanceOf(InvalidKnowledgeUploadException.class)
            .hasMessageContaining("expands");
    }

    @Test
    void emptyTextIsAClientError() {
        assertThatThrownBy(() -> InProcessTextExtractor.extract("a.html", "<p> </p>".getBytes(StandardCharsets.UTF_8)))
            .isInstanceOf(InvalidKnowledgeUploadException.class);
    }

    @Test
    void pdfAndImagesNeedTheSidecar() {
        assertThatThrownBy(() -> InProcessTextExtractor.extract("a.pdf", new byte[] {1}))
            .isInstanceOf(FeatureUnavailableException.class);
        assertThatThrownBy(() -> InProcessTextExtractor.extract("a.JPG", new byte[] {1}))
            .isInstanceOf(FeatureUnavailableException.class);
        assertThatThrownBy(() -> InProcessTextExtractor.extract("a.xyz", new byte[] {1}))
            .isInstanceOf(InvalidKnowledgeUploadException.class);
    }

    @Test
    void htmlKeepsTheOriginalElementRulesForHeaderAndUnclosedScripts() {
        String html = "<header>Top</header><p>Body</p><script>never closed <b>still text";

        String text = InProcessTextExtractor.htmlToText(html);

        // <header> is not <head>; an unclosed <script> is just a stripped tag, its content stays text.
        assertThat(text).contains("Top").contains("Body").contains("still text");
    }

    @Test
    void hostileUnterminatedMarkupIsLinearNotQuadratic() throws IOException {
        // 2MB each of shapes that make a document-wide lazy regex rescan to the end for every start.
        String lts = "<".repeat(2_000_000);
        String scripts = "<script>".repeat(250_000);
        String openRuns = "<w:t>".repeat(400_000);
        String attrRuns = "<w:t ".repeat(400_000);
        long start = System.nanoTime();

        InProcessTextExtractor.htmlToText(lts);
        InProcessTextExtractor.htmlToText(scripts);
        InProcessTextExtractor.docxToText(zip("word/document.xml", openRuns));
        InProcessTextExtractor.docxToText(zip("word/document.xml", attrRuns));

        assertThat((System.nanoTime() - start) / 1_000_000).as("elapsed ms").isLessThan(5_000);
    }

    @Test
    void docxIgnoresSelfClosingRunsAndUnclosedRuns() throws IOException {
        String xml = "<w:p><w:r><w:t xml:space=\"preserve\"/></w:r><w:r><w:t>kept</w:t></w:r></w:p><w:p><w:t>lost";

        assertThat(InProcessTextExtractor.docxToText(zip("word/document.xml", xml))).isEqualTo("kept");
    }
}
