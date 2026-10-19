package com.agenticai.core.graphmemory;

import com.agenticai.core.chat.structured.StructuredOutput;
import com.agenticai.core.json.JsonMappers;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;

/**
 * One cheap {@link ChatClient} call per {@code
 * remember_fact} invocation (see {@code RememberFactToolCallback} in the agents module) that
 * pulls (subject, relation, object) triples out of a remembered fact's plain-text content, e.g.
 * "Priya's manager is Raj" -&gt; {@code (Priya, MANAGER_OF_BY, Raj)}.
 *
 * <p>Fails open on any error, always: this is a supplementary enrichment step layered on top of
 * {@code AgentMemoryService.remember}, which has already succeeded by the time this runs - a
 * failed or empty extraction means the fact is simply not ALSO written to the graph, never that
 * remembering the fact itself fails.
 */
@Service
public class EntityExtractionService {

    private static final Logger log = LoggerFactory.getLogger(EntityExtractionService.class);
    private static final ObjectMapper MAPPER = JsonMappers.plain();

    private static final String EXTRACTION_SYSTEM_PROMPT = """
        Extract simple (subject, relation, object) relationship triples from the given fact, if any
        exist. relation should be a short SCREAMING_SNAKE_CASE label (e.g. MANAGER_OF_BY, WORKS_AT,
        PREFERS, LOCATED_IN). subject and object should be short entity names (a person, place,
        company, or thing), not full sentences.

        If the fact contains no clear relationship between two named entities (e.g. it's a vague
        preference or a fact about a single thing with no second entity), return an empty array.

        Respond with ONLY a JSON array, no other text, in this exact shape:
        [{"subject": "...", "relation": "...", "object": "..."}]

        Example input: "Priya's manager is Raj"
        Example output: [{"subject": "Priya", "relation": "MANAGER_OF_BY", "object": "Raj"}]

        Example input: "I prefer email over WhatsApp"
        Example output: []
        """;

    /** Returns an empty list on any error, blank input, or a null {@code chatClient} - never throws. */
    public List<GraphTriple> extractTriples(ChatClient chatClient, String factText) {
        if (chatClient == null || factText == null || factText.isBlank()) {
            return List.of();
        }
        try {
            String response = chatClient.prompt()
                .system(EXTRACTION_SYSTEM_PROMPT)
                .user(factText)
                .call()
                .content();

            if (response == null || response.isBlank()) {
                return List.of();
            }
            return parseTriples(response.trim());
        } catch (Exception ex) {
            log.warn("Entity extraction failed for graph memory - skipping graph write for this fact", ex);
            return List.of();
        }
    }

    private List<GraphTriple> parseTriples(String jsonResponse) {
        try {
            // Models occasionally wrap JSON in a markdown fence or a sentence of prose despite
            // instructions not to - tolerated by StructuredOutput rather than failing the whole
            // extraction over formatting alone.
            JsonNode array = StructuredOutput.readArray(MAPPER, jsonResponse).orElse(null);
            if (array == null) {
                // Size only: the response is LLM output derived from the user's chat (JAVA-033).
                log.warn("Could not parse entity extraction response as JSON - skipping graph write ({} chars)",
                    jsonResponse == null ? 0 : jsonResponse.length());
                return List.of();
            }
            List<GraphTriple> triples = new ArrayList<>();
            for (JsonNode node : array) {
                String subject = textOrNull(node, "subject");
                String relation = textOrNull(node, "relation");
                String object = textOrNull(node, "object");
                if (subject != null && relation != null && object != null) {
                    triples.add(new GraphTriple(subject, relation, object));
                }
            }
            return triples;
        } catch (Exception ex) {
            log.warn("Could not parse entity extraction response as JSON - skipping graph write ({} chars)",
                jsonResponse == null ? 0 : jsonResponse.length());
            return List.of();
        }
    }

    private String textOrNull(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return (value == null || value.isNull() || value.asText().isBlank()) ? null : value.asText().trim();
    }
}
