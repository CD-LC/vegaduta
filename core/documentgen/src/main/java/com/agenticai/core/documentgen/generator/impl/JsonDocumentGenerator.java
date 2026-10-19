package com.agenticai.core.documentgen.generator.impl;

import com.agenticai.core.documentgen.generator.DocumentGenerator;
import com.agenticai.core.documentgen.model.DocumentContent;
import com.agenticai.core.documentgen.model.DocumentGenerationRequest;
import com.agenticai.core.documentgen.model.DocumentType;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectWriter;
import java.nio.charset.StandardCharsets;
import org.springframework.stereotype.Component;

/**
 * Renders a {@link DocumentContent} into pretty-printed JSON bytes. Prefers
 * {@link DocumentContent#json()} when present - that value is serialized
 * verbatim as the entire file; otherwise falls back to pretty-printing the
 * whole {@link DocumentContent} (title/sections/table/rawText) as JSON.
 */
@Component
public class JsonDocumentGenerator implements DocumentGenerator {

    private final ObjectMapper objectMapper;

    public JsonDocumentGenerator(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public DocumentType supportedType() {
        return DocumentType.JSON;
    }

    @Override
    public byte[] generate(DocumentGenerationRequest request) throws Exception {
        DocumentContent content = request.content();
        Object payload = content.json() != null ? content.json() : content;

        ObjectWriter writer = objectMapper.writerWithDefaultPrettyPrinter();
        String json = writer.writeValueAsString(payload);
        return json.getBytes(StandardCharsets.UTF_8);
    }
}
