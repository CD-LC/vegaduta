package com.agenticai.core.documentgen.generator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.agenticai.core.documentgen.model.DocumentGenerationRequest;
import com.agenticai.core.documentgen.model.DocumentType;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * {@link DocumentGeneratorRegistry}'s javadoc makes two behavioral promises
 * that only a test keeps honest: an unknown format resolves to {@link
 * java.util.Optional#empty()} (never null / never a throw), and two beans
 * claiming the same {@link DocumentType} fail FAST at construction instead
 * of silently letting "last one wins" pick a renderer. Fakes are used
 * instead of the real {@code @Component} generators so the dispatch logic is
 * tested in isolation from any rendering library.
 */
class DocumentGeneratorRegistryTest {

    @Test
    void findReturnsTheGeneratorRegisteredForTheType() {
        DocumentGenerator csv = fake(DocumentType.CSV);
        DocumentGenerator txt = fake(DocumentType.TXT);
        DocumentGeneratorRegistry registry = new DocumentGeneratorRegistry(List.of(csv, txt));

        assertSame(csv, registry.find(DocumentType.CSV).orElseThrow());
        assertSame(txt, registry.find(DocumentType.TXT).orElseThrow());
    }

    @Test
    void findReturnsEmptyForAnUnregisteredType() {
        DocumentGeneratorRegistry registry = new DocumentGeneratorRegistry(List.of(fake(DocumentType.CSV)));

        assertTrue(registry.find(DocumentType.PDF).isEmpty());
    }

    @Test
    void anEmptyRegistryIsLegalAndAlwaysEmpty() {
        // Documented as the module's shipped state before generator.impl
        // gained its first @Component - must not be a construction error.
        DocumentGeneratorRegistry registry = new DocumentGeneratorRegistry(List.of());

        for (DocumentType type : DocumentType.values()) {
            assertTrue(registry.find(type).isEmpty(), type + " should be unresolvable in an empty registry");
        }
    }

    @Test
    void duplicateRegistrationForOneTypeFailsAtConstruction() {
        List<DocumentGenerator> clashing = List.of(fake(DocumentType.CSV), fake(DocumentType.CSV));

        assertThrows(IllegalStateException.class, () -> new DocumentGeneratorRegistry(clashing));
    }

    @Test
    void everyDocumentTypeHasANonBlankDottedExtensionAndMimeType() {
        // extension() feeds the stored filename and mimeType() becomes the
        // download Content-Type - a malformed constant here ships a file
        // browsers can't name or open correctly.
        for (DocumentType type : DocumentType.values()) {
            assertTrue(type.extension().startsWith("."), type + " extension must include the leading dot");
            assertTrue(type.extension().length() > 1, type + " extension must not be just a dot");
            assertTrue(type.mimeType().contains("/"), type + " mime type must be a type/subtype pair");
        }
        // Spot-check the two most download-sensitive mappings exactly.
        assertEquals("text/csv", DocumentType.CSV.mimeType());
        assertEquals("application/pdf", DocumentType.PDF.mimeType());
    }

    private static DocumentGenerator fake(DocumentType type) {
        return new DocumentGenerator() {
            @Override
            public DocumentType supportedType() {
                return type;
            }

            @Override
            public byte[] generate(DocumentGenerationRequest request) {
                return new byte[0];
            }
        };
    }
}
