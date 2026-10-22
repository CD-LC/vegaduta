package com.agenticai.core.documentgen.generator.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.agenticai.core.documentgen.model.DocumentContent;
import com.agenticai.core.documentgen.model.DocumentGenerationRequest;
import com.agenticai.core.documentgen.model.DocumentType;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * {@link JsonDocumentGenerator} has one load-bearing branch: {@link DocumentContent#json()} is
 * serialized VERBATIM as the whole file, and only when it is null does the generator fall back to
 * serializing the entire {@link DocumentContent}. Getting that backwards would wrap every agent-
 * produced JSON payload in a spurious {@code {"title":..., "json":...}} envelope. Assertions parse
 * the output back with Jackson rather than string-matching, so the pretty-printer's whitespace
 * choices can change freely without breaking the suite.
 */
class JsonDocumentGeneratorTest {

    private final ObjectMapper objectMapper = com.agenticai.core.json.JsonMappers.plain();
    private final JsonDocumentGenerator generator = new JsonDocumentGenerator(objectMapper);

    @Test
    void supportsJson() {
        assertEquals(DocumentType.JSON, generator.supportedType());
    }

    @Test
    void jsonPayloadIsSerializedVerbatimAsTheEntireFile() throws Exception {
        Map<String, Object> payload = Map.of("orders", List.of(Map.of("id", 1, "total", 99.5)));
        DocumentContent content =
                new DocumentContent("ignored", List.of(), null, "ignored", payload);

        JsonNode parsed = parse(generator.generate(request(content)));

        assertEquals(1, parsed.at("/orders/0/id").asInt());
        assertEquals(99.5, parsed.at("/orders/0/total").asDouble(), 1e-9);
        // The envelope fields must NOT leak into the file when json() is set.
        assertTrue(parsed.path("title").isMissingNode());
        assertTrue(parsed.path("rawText").isMissingNode());
    }

    @Test
    void withoutAJsonPayloadTheWholeContentIsSerialized() throws Exception {
        DocumentContent content =
                new DocumentContent(
                        "Report",
                        List.of(new DocumentContent.Section("H", List.of("p1"), List.of("b1"))),
                        null,
                        null,
                        null);

        JsonNode parsed = parse(generator.generate(request(content)));

        assertEquals("Report", parsed.path("title").asString());
        assertEquals("H", parsed.at("/sections/0/heading").asString());
        assertEquals("p1", parsed.at("/sections/0/paragraphs/0").asString());
    }

    private DocumentGenerationRequest request(DocumentContent content) {
        return new DocumentGenerationRequest(DocumentType.JSON, "test", content);
    }

    private JsonNode parse(byte[] bytes) throws Exception {
        return objectMapper.readTree(new String(bytes, StandardCharsets.UTF_8));
    }
}
