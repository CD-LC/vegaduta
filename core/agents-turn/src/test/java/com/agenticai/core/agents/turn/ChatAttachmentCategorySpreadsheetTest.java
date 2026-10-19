package com.agenticai.core.agents.turn;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.agenticai.core.agents.modality.ChatAttachmentCategory;
import com.agenticai.core.agents.modality.UnsupportedAttachmentTypeException;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.util.MimeType;

class ChatAttachmentCategorySpreadsheetTest {

    private static final String XLSX = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";

    @Test
    void xlsxIsAcceptedAsASandboxOnlySpreadsheet() {
        assertEquals(ChatAttachmentCategory.SPREADSHEET, ChatAttachmentCategory.fromContentType(XLSX));
        assertEquals(ChatAttachmentCategory.SPREADSHEET, ChatAttachmentCategory.fromContentType(XLSX.toUpperCase() + "; x=y"));
        assertTrue(ChatAttachmentCategory.isSandboxOnly(XLSX));
    }

    @Test
    void csvAndPdfStillGoToTheModelAsDocuments() {
        assertEquals(ChatAttachmentCategory.DOCUMENT, ChatAttachmentCategory.fromContentType("text/csv"));
        assertFalse(ChatAttachmentCategory.isSandboxOnly("text/csv"));
        assertFalse(ChatAttachmentCategory.isSandboxOnly(null));
    }

    @Test
    void otherOfficeTypesAreStillRejected() {
        assertThrows(UnsupportedAttachmentTypeException.class,
            () -> ChatAttachmentCategory.fromContentType("application/vnd.ms-excel"));
        assertThrows(UnsupportedAttachmentTypeException.class,
            () -> ChatAttachmentCategory.fromContentType("application/zip"));
    }

    @Test
    void sandboxOnlyAttachmentsNeverReachTheModel() {
        ChatAttachmentPayload sheet = new ChatAttachmentPayload(MimeType.valueOf(XLSX), new ByteArrayResource(new byte[] {1}), "q1.xlsx");
        ChatAttachmentPayload csv = new ChatAttachmentPayload(MimeType.valueOf("text/csv"), new ByteArrayResource(new byte[] {1}), "q1.csv");
        List<ChatAttachmentPayload> forModel = ChatAttachments.withoutSandboxOnly(List.of(sheet, csv));
        assertEquals(List.of(csv), forModel);
        List<ChatAttachmentPayload> unchanged = List.of(csv);
        assertEquals(unchanged, ChatAttachments.withoutSandboxOnly(unchanged));
    }
}
