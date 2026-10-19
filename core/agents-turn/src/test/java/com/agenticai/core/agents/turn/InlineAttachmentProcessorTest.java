package com.agenticai.core.agents.turn;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.agenticai.core.agents.dto.ChatInlineAttachment;
import com.agenticai.core.agents.modality.ModelModalities;
import com.agenticai.core.agents.modality.ModelModalitiesService;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The 413 / 415 / 400 contract for inline chat attachments (multimodal
 * playground contract) - see {@link InlineAttachmentProcessor}'s javadoc for
 * which exception carries which status ({@code GlobalExceptionHandler} maps
 * {@link AttachmentPayloadTooLargeException} to 413, {@link
 * AttachmentModalityNotSupportedException} to 415, {@link
 * MalformedAttachmentException} to 400).
 */
class InlineAttachmentProcessorTest {

    private static final String MODEL = "gpt-4o";

    private ModelModalitiesService modelModalitiesService;
    private InlineAttachmentProcessor processor;

    @BeforeEach
    void setUp() {
        modelModalitiesService = mock(ModelModalitiesService.class);
        // Default profile for these tests: image-only (the gpt-4o shape).
        when(modelModalitiesService.resolve(any(), any()))
            .thenReturn(new ModelModalities(true, false, false, false));
        processor = new InlineAttachmentProcessor(modelModalitiesService);
    }

    private static ChatInlineAttachment image(String name, byte[] bytes) {
        return new ChatInlineAttachment(name, "image/jpeg", Base64.getEncoder().encodeToString(bytes));
    }

    // --- happy path ---

    @Test
    void decodesAValidImageAttachment() {
        byte[] bytes = "fake-jpeg-bytes".getBytes(StandardCharsets.UTF_8);
        List<InlineAttachmentProcessor.ProcessedAttachment> out =
            processor.process(List.of(image("photo.jpg", bytes)), null, MODEL);

        assertEquals(1, out.size());
        assertEquals("photo.jpg", out.get(0).fileName());
        assertEquals("image/jpeg", out.get(0).mimeType().toString());
        assertEquals("photo.jpg", out.get(0).resource().getFilename());
    }

    @Test
    void toleratesADataUriPrefixAndWhitespace() {
        byte[] bytes = "payload".getBytes(StandardCharsets.UTF_8);
        String wrapped = "data:image/png;base64," + Base64.getEncoder().encodeToString(bytes).replace("cGF5", "cGF5\n");
        List<InlineAttachmentProcessor.ProcessedAttachment> out = processor.process(
            List.of(new ChatInlineAttachment(null, "image/png", wrapped)), null, MODEL);
        assertEquals(1, out.size());
        // No fileName supplied - defaulted, never null.
        assertEquals("attachment", out.get(0).fileName());
    }

    @Test
    void nullOrEmptyListIsANoOp() {
        assertTrue(processor.process(null, null, MODEL).isEmpty());
        assertTrue(processor.process(List.of(), null, MODEL).isEmpty());
    }

    @Test
    void pdfIsAcceptedWhenTheModelIsDocumentCapable() {
        when(modelModalitiesService.resolve(any(), any()))
            .thenReturn(new ModelModalities(true, true, true, true));
        List<InlineAttachmentProcessor.ProcessedAttachment> out = processor.process(
            List.of(new ChatInlineAttachment("doc.pdf", "application/pdf",
                Base64.getEncoder().encodeToString("pdf".getBytes(StandardCharsets.UTF_8)))),
            null, "gemini-2.5-pro");
        assertEquals("application/pdf", out.get(0).mimeType().toString());
    }

    // --- 413: too many / too big ---

    @Test
    void rejectsMoreThanThreeAttachments() {
        byte[] bytes = "x".getBytes(StandardCharsets.UTF_8);
        List<ChatInlineAttachment> four = List.of(
            image("1.jpg", bytes), image("2.jpg", bytes), image("3.jpg", bytes), image("4.jpg", bytes));
        AttachmentPayloadTooLargeException e = assertThrows(
            AttachmentPayloadTooLargeException.class, () -> processor.process(four, null, MODEL));
        assertTrue(e.getMessage().contains("at most 3"));
    }

    @Test
    void rejectsOverTenMegabytesTotalDecoded() {
        // Two 6MB payloads: each fine alone, 12MB together - the TOTAL cap trips.
        byte[] sixMb = new byte[6 * 1024 * 1024];
        AttachmentPayloadTooLargeException e = assertThrows(
            AttachmentPayloadTooLargeException.class,
            () -> processor.process(List.of(image("a.jpg", sixMb), image("b.jpg", sixMb)), null, MODEL));
        assertTrue(e.getMessage().contains("10MB"));
        assertTrue(e.getMessage().contains("b.jpg"), "should name the attachment that tripped the cap");
    }

    // --- 415: modality the model can't read ---

    @Test
    void rejectsAudioForAnImageOnlyModelNamingTheModel() {
        ChatInlineAttachment audio = new ChatInlineAttachment("note.mp3", "audio/mpeg",
            Base64.getEncoder().encodeToString("mp3".getBytes(StandardCharsets.UTF_8)));
        AttachmentModalityNotSupportedException e = assertThrows(
            AttachmentModalityNotSupportedException.class, () -> processor.process(List.of(audio), null, MODEL));
        assertTrue(e.getMessage().contains(MODEL), "message must name the model");
        assertTrue(e.getMessage().contains("audio"));
        assertTrue(e.getMessage().contains("images"), "message must say what IS supported");
    }

    @Test
    void rejectsPdfWhenTheModelIsNotDocumentCapable() {
        ChatInlineAttachment pdf = new ChatInlineAttachment("doc.pdf", "application/pdf",
            Base64.getEncoder().encodeToString("pdf".getBytes(StandardCharsets.UTF_8)));
        assertThrows(AttachmentModalityNotSupportedException.class, () -> processor.process(List.of(pdf), null, MODEL));
    }

    @Test
    void rejectsEverythingForATextOnlyModel() {
        when(modelModalitiesService.resolve(any(), any())).thenReturn(ModelModalities.NONE);
        byte[] bytes = "x".getBytes(StandardCharsets.UTF_8);
        AttachmentModalityNotSupportedException e = assertThrows(
            AttachmentModalityNotSupportedException.class,
            () -> processor.process(List.of(image("p.jpg", bytes)), null, "sarvam-105b"));
        assertTrue(e.getMessage().contains("text only"));
    }

    @Test
    void rejectsAnUnrecognizedMimeTypeOutright() {
        ChatInlineAttachment weird = new ChatInlineAttachment("x.docx",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            Base64.getEncoder().encodeToString("d".getBytes(StandardCharsets.UTF_8)));
        assertThrows(AttachmentModalityNotSupportedException.class, () -> processor.process(List.of(weird), null, MODEL));
    }

    // --- 400: malformed input ---

    @Test
    void rejectsInvalidBase64() {
        ChatInlineAttachment bad = new ChatInlineAttachment("p.jpg", "image/jpeg", "!!!not-base64!!!");
        MalformedAttachmentException e = assertThrows(
            MalformedAttachmentException.class, () -> processor.process(List.of(bad), null, MODEL));
        assertTrue(e.getMessage().contains("base64"));
    }

    @Test
    void rejectsMissingMimeTypeOrData() {
        assertThrows(MalformedAttachmentException.class, () -> processor.process(
            List.of(new ChatInlineAttachment("p.jpg", null, "aGk=")), null, MODEL));
        assertThrows(MalformedAttachmentException.class, () -> processor.process(
            List.of(new ChatInlineAttachment("p.jpg", "image/jpeg", " ")), null, MODEL));
    }

    @Test
    void rejectsAZeroByteDecodedPayload() {
        // "data:...," with an empty payload half decodes to zero bytes.
        assertThrows(MalformedAttachmentException.class, () -> processor.process(
            List.of(new ChatInlineAttachment("p.jpg", "image/jpeg", "data:image/jpeg;base64,")), null, MODEL));
    }

    // --- source (live-vision contract) ---

    @Test
    void acceptsEveryLegalSourceValueIncludingAbsent() {
        byte[] bytes = "frame".getBytes(StandardCharsets.UTF_8);
        String data = Base64.getEncoder().encodeToString(bytes);
        List<ChatInlineAttachment> all = List.of(
            new ChatInlineAttachment("up.jpg", "image/jpeg", data, "upload"),
            new ChatInlineAttachment("cam.jpg", "image/jpeg", data, "camera"),
            new ChatInlineAttachment("scr.jpg", "image/jpeg", data, "screen"));
        assertEquals(3, processor.process(all, null, MODEL).size());
        // Absent/null (the 3-arg pre-contract shape) still passes untouched.
        assertEquals(1, processor.process(List.of(image("old.jpg", bytes)), null, MODEL).size());
    }

    @Test
    void rejectsAnUnknownSourceOnThe400Path() {
        ChatInlineAttachment bad = new ChatInlineAttachment("p.jpg", "image/jpeg",
            Base64.getEncoder().encodeToString("x".getBytes(StandardCharsets.UTF_8)), "webcam");
        MalformedAttachmentException e = assertThrows(
            MalformedAttachmentException.class, () -> processor.process(List.of(bad), null, MODEL));
        assertTrue(e.getMessage().contains("webcam"));
        assertTrue(e.getMessage().contains("camera"), "message should list the legal values");
    }
}
