package com.agenticai.core.graphmemory;

import static org.assertj.core.api.Assertions.assertThat;

import com.agenticai.core.prompts.PromptCatalog;
import com.agenticai.core.prompts.PromptContext;
import com.agenticai.core.prompts.PromptResolver;
import com.agenticai.core.prompts.ResolvedPrompt;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Golden test of the migration of the graph-memory prompts (triple extraction, write arbitration)
 * onto the prompt registry: the text each key resolves to by default is, byte for byte, the
 * literal that was hardcoded before. The literals below are the Java source of those constants as
 * they stood when they moved into src/main/resources/prompts/en/. If this fails, a default prompt
 * changed.
 */
class MemoryPromptGoldenTest {

    // memory.entity_extraction  <-  src/main/java/com/agenticai/core/graphmemory/EntityExtractionService.java EXTRACTION_SYSTEM_PROMPT
    private static final String C_extract =
"""
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

    // memory.arbitration  <-  src/main/java/com/agenticai/core/graphmemory/MemoryWriteArbitrationService.java ARBITRATION_SYSTEM_PROMPT
    private static final String C_arbitrate =
"""
        You are deciding how to write new candidate facts into a long-term memory graph, given the
        facts already known about the same subjects. For EACH candidate fact, decide exactly one
        action:

        - "ADD": a genuinely new fact - no existing fact covers the same (subject, relation) pair.
        - "UPDATE": an existing fact has the same subject and relation but a DIFFERENT object - the
          candidate supersedes it (e.g. a changed preference, a changed manager, a changed shop
          location). The old fact should be replaced, not kept alongside the new one.
        - "MERGE": the candidate restates or elaborates an existing fact about the same (subject,
          relation) with an equivalent or compatible object - treat this the same as UPDATE (the
          newer, more complete wording replaces the older one).
        - "DISCARD": the candidate is redundant with an existing fact - same subject, relation, AND
          an equivalent object, or a trivial rewording with no new information.

        Respond with ONLY a JSON array, one entry per candidate fact, in the same order given, in
        this exact shape - no other text, no markdown fence:
        [{"subject": "...", "relation": "...", "object": "...", "decision": "ADD"|"UPDATE"|"MERGE"|"DISCARD"}]
        """;

    static Stream<Arguments> literals() {
        return Stream.of(
            Arguments.of("memory.entity_extraction", C_extract),
            Arguments.of("memory.arbitration", C_arbitrate)
        );
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("literals")
    void shippedDefaultIsTheFormerLiteral(String key, String literal) {
        PromptResolver resolver = PromptResolver.defaultsOnly();
        assertThat(resolver.catalog().defaultText(key)).isEqualTo(literal);
        ResolvedPrompt resolved = resolver.resolve(key, PromptContext.none());
        assertThat(resolved.textOrNull()).isEqualTo(literal);
    }

    @Test
    void catalogIsSound() {
        PromptCatalog catalog = PromptResolver.defaultsOnly().catalog();
        assertThat(catalog.problems()).isEmpty();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("literals")
    void keyIsRegisteredAsSecondaryCallWithADescription(String key, String literal) {
        var def = PromptResolver.defaultsOnly().catalog().find(key).orElseThrow();
        assertThat(def.description()).isNotBlank();
        assertThat(def.title()).isNotBlank();
        assertThat(def.disableable()).isFalse();
    }
}
