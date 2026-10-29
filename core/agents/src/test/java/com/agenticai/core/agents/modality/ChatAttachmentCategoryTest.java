package com.agenticai.core.agents.modality;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * {@link ChatAttachmentCategory} maps an upload's HTTP content type onto the attachment kind a
 * chat turn carries. These pin the wildcard top-level types, the allow-listed document types
 * (with charset parameters stripped), the sandbox-only spreadsheet, and that everything else is
 * rejected rather than guessed.
 */
class ChatAttachmentCategoryTest {

    @Test
    void mediaTopLevelTypesMapOneToOne() {
        assertEquals(ChatAttachmentCategory.IMAGE, ChatAttachmentCategory.fromContentType("image/png"));
        assertEquals(ChatAttachmentCategory.IMAGE, ChatAttachmentCategory.fromContentType("IMAGE/JPEG"));
        assertEquals(ChatAttachmentCategory.AUDIO, ChatAttachmentCategory.fromContentType("audio/mpeg"));
        assertEquals(ChatAttachmentCategory.VIDEO, ChatAttachmentCategory.fromContentType("video/mp4"));
    }

    @Test
    void theAllowListedDocumentTypesAreDocuments() {
        assertEquals(ChatAttachmentCategory.DOCUMENT, ChatAttachmentCategory.fromContentType("application/pdf"));
        assertEquals(ChatAttachmentCategory.DOCUMENT, ChatAttachmentCategory.fromContentType("text/plain"));
        assertEquals(ChatAttachmentCategory.DOCUMENT, ChatAttachmentCategory.fromContentType("application/json"));
        // A browser may append a charset parameter to the text types.
        assertEquals(ChatAttachmentCategory.DOCUMENT,
            ChatAttachmentCategory.fromContentType("text/csv; charset=utf-8"));
    }

    @Test
    void aWorkbookIsSandboxOnlyNeverModelInput() {
        String xlsx = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";

        assertEquals(ChatAttachmentCategory.SPREADSHEET, ChatAttachmentCategory.fromContentType(xlsx));
        assertTrue(ChatAttachmentCategory.isSandboxOnly(xlsx));
        assertTrue(ChatAttachmentCategory.isSandboxOnly(xlsx + "; charset=utf-8"));
        assertFalse(ChatAttachmentCategory.isDocument(xlsx));
    }

    @Test
    void arbitraryApplicationAndTextTypesAreRejected() {
        // application/* is far wider than the document allow-list; so is a .zip.
        assertThrows(UnsupportedAttachmentTypeException.class,
            () -> ChatAttachmentCategory.fromContentType("application/zip"));
        assertThrows(UnsupportedAttachmentTypeException.class,
            () -> ChatAttachmentCategory.fromContentType("application/msword"));
        assertThrows(UnsupportedAttachmentTypeException.class,
            () -> ChatAttachmentCategory.fromContentType("text/html"));
        assertThrows(UnsupportedAttachmentTypeException.class,
            () -> ChatAttachmentCategory.fromContentType("font/woff2"));
    }

    @Test
    void nullAndBlankContentTypesAreRejectedNotGuessed() {
        assertThrows(UnsupportedAttachmentTypeException.class,
            () -> ChatAttachmentCategory.fromContentType(null));
        assertThrows(UnsupportedAttachmentTypeException.class,
            () -> ChatAttachmentCategory.fromContentType("   "));
        assertFalse(ChatAttachmentCategory.isDocument(null));
        assertFalse(ChatAttachmentCategory.isSandboxOnly(null));
    }
}
