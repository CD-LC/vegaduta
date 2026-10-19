package com.agenticai.core.workflow;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

import com.agenticai.core.agents.tools.web.HttpRequestExecutor;
import com.agenticai.core.storage.FileStorageService;
import com.agenticai.core.workflow.WorkflowDocumentText.DocumentExtractionException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

/** {@link AiReferenceResolver}: classification of the document source and the safety checks before any I/O. */
class AiReferenceResolverTest {

    @SuppressWarnings("unchecked")
    private static AiReferenceResolver resolver() {
        return new AiReferenceResolver(mock(ObjectProvider.class), mock(ObjectProvider.class), new ObjectMapper(), 1024);
    }

    @Test
    void classifiesUrlStorageKeyAndRawText() {
        AiReferenceResolver r = resolver();
        AiReferenceResolver.Reference url = r.resolve(Map.of("source", "https://x.example/a.txt\nmore"), "");
        assertEquals(AiReferenceResolver.RefKind.URL, url.kind());
        assertEquals("https://x.example/a.txt", url.value());
        assertEquals(AiReferenceResolver.RefKind.STORAGE, r.resolve(Map.of("source", "chat-attachments/a/b.pdf"), "").kind());
        assertEquals(AiReferenceResolver.RefKind.TEXT, r.resolve(Map.of(), "just words").kind());
        assertNull(r.resolve(Map.of(), "  "));
    }

    @Test
    void unpacksJsonReferencesInPreferenceOrderKeyUrlText() {
        AiReferenceResolver r = resolver();
        assertEquals(AiReferenceResolver.RefKind.STORAGE,
            r.resolve(Map.of("source", "{\"attachmentKey\":\"chat-attachments/k.txt\",\"url\":\"http://u\"}"), "").kind());
        AiReferenceResolver.Reference u = r.resolve(Map.of("source", "{\"url\":\"http://u\"}"), "");
        assertEquals("http://u", u.fileName());
        assertEquals("hi", r.resolve(Map.of("source", "{\"content\":\"hi\"}"), "").value());
        assertNull(r.resolve(Map.of("source", "{\"other\":1}"), ""));
    }

    @Test
    void storageKeysAreValidatedBeforeAnyStorageAccess() {
        AiReferenceResolver r = resolver();
        assertThrows(DocumentExtractionException.class, () -> r.readStored("chat-attachments/../etc"));
        assertThrows(DocumentExtractionException.class, () -> r.readStored("other/key"));
        assertThrows(DocumentExtractionException.class, () -> r.readStored("chat-attachments/a//b"));
    }

    @Test
    void urlsWithTemplateReferencesAreRefused() {
        assertThrows(DocumentExtractionException.class, () -> resolver().fetchUrl("http://x/{{secret.K}}"));
    }

    @Test
    void hostOfFallsBackForUnparseableUrls() {
        assertEquals("x.example", AiReferenceResolver.hostOf("https://x.example/p"));
        assertEquals("the URL", AiReferenceResolver.hostOf("not a url ::"));
    }
}
