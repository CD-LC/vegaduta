package com.agenticai.core.documentgen.generator;

import com.agenticai.core.documentgen.model.DocumentGenerationRequest;
import com.agenticai.core.documentgen.model.DocumentType;

/**
 * One per {@link DocumentType} - renders a {@link DocumentGenerationRequest}
 * into the finished file's bytes. Implementations live in {@code
 * generator.impl} (e.g. {@code DocxDocumentGenerator}, {@code
 * PdfDocumentGenerator}) as plain {@code @Component} beans; {@link
 * DocumentGeneratorRegistry} discovers all of them and dispatches by {@link
 * #supportedType()} - no implementation registers itself anywhere else.
 *
 * <p>{@code generate} is called off the request thread, from {@code
 * DocumentGenerationEventListener}'s {@code @Async} handler - implementations
 * do not need to worry about blocking, but any exception they throw is
 * caught by that listener and turned into the job's {@code FAILED} status
 * with {@code getMessage()} as the stored {@code errorMessage}, so messages
 * should be written for a person, not a raw library stack trace.
 */
public interface DocumentGenerator {

    /** Which {@link DocumentType} this generator produces - the registry key. */
    DocumentType supportedType();

    /** @return the finished file's raw bytes. @throws Exception on any rendering failure - caught and turned into a FAILED job by the caller. */
    byte[] generate(DocumentGenerationRequest request) throws Exception;

    /**
     * Same, with the brand to put in the document's header (tenant logo/name, or
     * VegaDuta's). Visual formats override this; data formats (CSV/JSON/TXT/XLSX)
     * keep the default, which ignores the brand.
     */
    default byte[] generate(DocumentGenerationRequest request, com.agenticai.core.documentgen.brand.DocumentBrand brand)
        throws Exception {
        return generate(request);
    }
}
