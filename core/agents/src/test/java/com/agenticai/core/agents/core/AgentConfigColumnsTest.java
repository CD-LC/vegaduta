package com.agenticai.core.agents.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.agenticai.core.providers.modality.TextModelProvider;
import com.agenticai.core.providers.modality.VoiceModelProvider;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * {@link AgentConfigColumns} is the mapped superclass carrying {@link Agent}'s allow-lists,
 * modality overrides, schemas and audit actors. These pin the two opposite empty-collection
 * conventions (knowledge collections: empty = unrestricted; code-intel repos and write collections:
 * empty = deny-all), the unmodifiable getters, the null-collapsing setters, and that every
 * configuration-changing setter stamps the entity via {@code touch()}.
 */
class AgentConfigColumnsTest {

    /** A concrete instance with a touch() counter, standing in for the JPA subclass. */
    private static final class TestColumns extends AgentConfigColumns {
        private final AtomicInteger touches = new AtomicInteger();

        @Override
        protected void touch() {
            touches.incrementAndGet();
        }
    }

    @Test
    void allowListsStartEmptyAndUnmodifiable() {
        TestColumns c = new TestColumns();

        assertTrue(c.getAllowedToolNames().isEmpty());
        assertTrue(c.getAllowedKnowledgeCollectionIds().isEmpty());
        assertTrue(c.getAllowedCodeIntelRepoIds().isEmpty());
        assertTrue(c.getAllowedKnowledgeWriteCollectionIds().isEmpty());
        assertTrue(c.getCollaboratorAgentIds().isEmpty());
        assertTrue(c.getEnabledGuardrails().isEmpty());
        assertThrows(UnsupportedOperationException.class, () -> c.getAllowedToolNames().add("x"));
        assertThrows(UnsupportedOperationException.class, () -> c.getEnabledGuardrails().add("g"));
    }

    @Test
    void settersCopyNullToEmptyAndTouch() {
        TestColumns c = new TestColumns();
        UUID collection = UUID.randomUUID();
        UUID collaborator = UUID.randomUUID();

        c.setAllowedToolNames(Set.of("web_search", "fetch_web_page"));
        c.setAllowedKnowledgeCollectionIds(Set.of(collection));
        c.setAllowedCodeIntelRepoIds(Set.of("repo-1"));
        c.setAllowedKnowledgeWriteCollectionIds(Set.of(collection));
        c.setCollaboratorAgentIds(Set.of(collaborator));
        c.setEnabledGuardrails(Set.of("profanity"));

        assertEquals(Set.of("web_search", "fetch_web_page"), c.getAllowedToolNames());
        assertEquals(Set.of(collection), c.getAllowedKnowledgeCollectionIds());
        assertEquals(Set.of("repo-1"), c.getAllowedCodeIntelRepoIds());
        assertEquals(Set.of(collection), c.getAllowedKnowledgeWriteCollectionIds());
        assertEquals(Set.of(collaborator), c.getCollaboratorAgentIds());
        assertEquals(Set.of("profanity"), c.getEnabledGuardrails());
        assertTrue(c.touches.get() >= 6);

        // Null collapses back to the empty (never-null) state.
        c.setAllowedToolNames(null);
        c.setCollaboratorAgentIds(null);
        assertTrue(c.getAllowedToolNames().isEmpty());
        assertTrue(c.getCollaboratorAgentIds().isEmpty());
    }

    @Test
    void setterInputIsDefensivelyCopied() {
        TestColumns c = new TestColumns();
        java.util.HashSet<String> tools = new java.util.HashSet<>(Set.of("a"));
        c.setAllowedToolNames(tools);
        tools.add("b");
        assertEquals(Set.of("a"), c.getAllowedToolNames());
    }

    @Test
    void modalitySettersDelegateToTheValueObjectAndClearWorks() {
        TestColumns c = new TestColumns();

        assertNull(c.getVoiceModelProvider());
        assertNull(c.getTextModelProvider());
        assertNotNull(c.modalityModels());

        c.setVoiceModelProvider(VoiceModelProvider.SARVAM, "saaras:v3");
        c.setTextModelProvider(TextModelProvider.ANTHROPIC, "claude-opus-4-8");

        assertEquals(VoiceModelProvider.SARVAM, c.getVoiceModelProvider());
        assertEquals("saaras:v3", c.getVoiceModel());
        assertEquals(TextModelProvider.ANTHROPIC, c.getTextModelProvider());
        assertEquals("claude-opus-4-8", c.getTextModel());
        assertTrue(c.touches.get() >= 2);

        c.clearVoiceModelProvider();
        assertNull(c.getVoiceModelProvider());
        assertNull(c.getVoiceModel());
        // The text override survives an unrelated clear.
        assertEquals(TextModelProvider.ANTHROPIC, c.getTextModelProvider());
        c.clearTextModelProvider();
        assertNull(c.getTextModelProvider());
    }

    @Test
    void auditActorsStartNullAndAreRecordedIndependently() {
        TestColumns c = new TestColumns();

        assertNull(c.getCreatedBy());
        assertNull(c.getUpdatedBy());

        c.setCreatedBy("creator-sub");
        c.setUpdatedBy("editor-sub");
        assertEquals("creator-sub", c.getCreatedBy());
        assertEquals("editor-sub", c.getUpdatedBy());

        c.setUpdatedBy("second-editor");
        assertEquals("creator-sub", c.getCreatedBy());
        assertEquals("second-editor", c.getUpdatedBy());
    }

    @Test
    void schemasStartEmptyAndGeneratedBumpsTheVersion() {
        TestColumns c = new TestColumns();

        assertNull(c.getInputSchemaJson());
        assertNull(c.getOutputSchemaJson());
        assertEquals(0, c.getSchemaVersion());
        assertNull(c.getResultSchemaJson());

        c.setGeneratedSchema("{\"in\":1}", "{\"out\":2}");
        assertEquals("{\"in\":1}", c.getInputSchemaJson());
        assertEquals("{\"out\":2}", c.getOutputSchemaJson());
        assertEquals(1, c.getSchemaVersion());

        // The tenant-authored result schema is NOT the generated output schema and is not bumped.
        c.setResultSchemaJson("{\"type\":\"object\"}");
        assertEquals("{\"type\":\"object\"}", c.getResultSchemaJson());
        assertEquals(1, c.getSchemaVersion());

        c.setGeneratedSchema("{\"in\":3}", "{\"out\":4}");
        assertEquals(2, c.getSchemaVersion());
        assertEquals(
                "{\"type\":\"object\"}",
                c.getResultSchemaJson(),
                "regenerating schemas must not destroy the tenant-authored job schema");
    }
}
