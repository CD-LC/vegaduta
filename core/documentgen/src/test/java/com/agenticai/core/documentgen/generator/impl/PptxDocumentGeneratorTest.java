package com.agenticai.core.documentgen.generator.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.agenticai.core.documentgen.generator.DocumentImageFetcher;
import com.agenticai.core.documentgen.model.DocumentContent;
import com.agenticai.core.documentgen.model.DocumentGenerationRequest;
import com.agenticai.core.documentgen.model.DocumentType;
import com.agenticai.core.security.net.SsrfGuard;
import java.io.ByteArrayInputStream;
import java.util.Base64;
import java.util.List;
import org.apache.poi.sl.usermodel.PictureData;
import org.apache.poi.xslf.usermodel.XSLFPictureShape;
import org.apache.poi.xslf.usermodel.XSLFSlide;
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.junit.jupiter.api.Test;

/** Section {@code imageUrl} → the deck embeds the fetched image on that section's slide. */
class PptxDocumentGeneratorTest {

    // 1x1 white PNG.
    private static final byte[] TINY_PNG =
            Base64.getDecoder()
                    .decode(
                            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==");

    private static class StubFetcher extends DocumentImageFetcher {
        private final Image canned;

        StubFetcher(Image canned) {
            super(SsrfGuard.strict());
            this.canned = canned;
        }

        @Override
        public Result fetch(String url) {
            return canned == null
                    ? new Result(null, false, "down")
                    : new Result(canned, true, null);
        }
    }

    private static DocumentGenerationRequest request(String imageUrl) {
        DocumentContent content =
                new DocumentContent(
                        "Deck",
                        List.of(
                                new DocumentContent.Section(
                                        "Slide one",
                                        List.of("Some text."),
                                        List.of("point"),
                                        imageUrl)),
                        null,
                        null,
                        null);
        return new DocumentGenerationRequest(DocumentType.PPTX, "deck", content);
    }

    private static long pictureCount(byte[] pptx) throws Exception {
        try (XMLSlideShow ppt = new XMLSlideShow(new ByteArrayInputStream(pptx))) {
            long n = 0;
            for (XSLFSlide slide : ppt.getSlides()) {
                n += slide.getShapes().stream().filter(s -> s instanceof XSLFPictureShape).count();
            }
            return n;
        }
    }

    @Test
    void sectionImageIsEmbeddedOnItsSlide() throws Exception {
        // The brand stamp draws one picture per slide; a section image adds exactly one more.
        DocumentImageFetcher.Image png =
                new DocumentImageFetcher.Image(TINY_PNG, PictureData.PictureType.PNG);
        byte[] withImage = new PptxDocumentGenerator(new StubFetcher(png)).generate(
                request("https://example.com/p.png"));
        byte[] without = new PptxDocumentGenerator(new StubFetcher(png)).generate(request(null));
        assertEquals(pictureCount(without) + 1, pictureCount(withImage));
    }

    @Test
    void aFailedFetchLeavesTheDeckIntact() throws Exception {
        byte[] failed = new PptxDocumentGenerator(new StubFetcher(null)).generate(
                request("https://example.com/gone.png"));
        byte[] without = new PptxDocumentGenerator(new StubFetcher(null)).generate(request(null));
        assertEquals(pictureCount(without), pictureCount(failed));
    }
}
