package com.agenticai.core.knowledge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.agenticai.core.knowledge.EmbeddingModelCatalog.ModelOption;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * {@link EmbeddingModelCatalog} is the single source of truth {@link
 * KnowledgeCollectionService#create} validates a requested embedding model
 * key against, and multi-model search ({@link KnowledgeSearchService})
 * groups collections by exactly the keys this class hands out. It had zero
 * coverage despite being the class whose drift from {@code
 * sidecar/app/embeddings.py}'s {@code MODEL_REGISTRY} (per this class's own
 * javadoc) would silently break collection creation or search for whichever
 * model went out of sync - worth locking down even though it's a static
 * list, precisely because "it's just a static list" is what makes a typo'd
 * key or a missing {@code isDefault} go unnoticed until a user hits it.
 *
 * <p>Pure, dependency-free logic - no mocking needed anywhere in this suite.
 */
class EmbeddingModelCatalogTest {

    @Test
    void allReturnsExactlyTheFiveDocumentedLocalModels() {
        List<ModelOption> models = EmbeddingModelCatalog.all();

        assertEquals(5, models.size());
        Set<String> keys = models.stream().map(ModelOption::key).collect(Collectors.toSet());
        assertEquals(
            Set.of("minilm-l6-v2", "mpnet-base-v2", "bge-small-en", "multi-qa-minilm", "multilingual-minilm"), keys
        );
    }

    @Test
    void exactlyOneModelIsMarkedDefaultAndItMatchesDefaultKey() {
        List<ModelOption> defaults = EmbeddingModelCatalog.all().stream().filter(ModelOption::isDefault).toList();

        assertEquals(1, defaults.size());
        assertEquals(EmbeddingModelCatalog.DEFAULT_KEY, defaults.get(0).key());
        assertEquals("minilm-l6-v2", EmbeddingModelCatalog.DEFAULT_KEY);
    }

    @Test
    void everyModelsNativeDimensionsAreLessThanOrEqualToStorageDimensions() {
        // The sidecar zero-pads every native embedding up to STORAGE_DIMENSIONS
        // (see this class's javadoc) - a model whose native output exceeded
        // that width would silently corrupt the padding assumption every
        // vector-column read/write in this module relies on.
        for (ModelOption model : EmbeddingModelCatalog.all()) {
            assertTrue(
                model.nativeDimensions() <= EmbeddingModelCatalog.STORAGE_DIMENSIONS,
                model.key() + " has nativeDimensions=" + model.nativeDimensions() + " exceeding STORAGE_DIMENSIONS=768"
            );
        }
        assertEquals(768, EmbeddingModelCatalog.STORAGE_DIMENSIONS);
    }

    @Test
    void isValidReturnsTrueForEveryRealCatalogKey() {
        for (ModelOption model : EmbeddingModelCatalog.all()) {
            assertTrue(EmbeddingModelCatalog.isValid(model.key()), model.key() + " should be recognized as valid");
        }
    }

    @Test
    void isValidReturnsFalseForAnUnknownOrMistypedKey() {
        assertFalse(EmbeddingModelCatalog.isValid("minilm-l6-v3"));
        assertFalse(EmbeddingModelCatalog.isValid("text-embedding-3-small"));
        assertFalse(EmbeddingModelCatalog.isValid(""));
    }

    @Test
    void noTwoModelsShareTheSameKey() {
        // A duplicate key would make EmbeddingModelCatalog.isValid() correct
        // by accident while KnowledgeCollectionService.create() silently
        // picked whichever duplicate stream().anyMatch() or a hypothetical
        // lookup-by-key happened to hit first.
        long distinctKeyCount = EmbeddingModelCatalog.all().stream().map(ModelOption::key).distinct().count();
        assertEquals(EmbeddingModelCatalog.all().size(), distinctKeyCount);
    }

    @Test
    void everyModelHasANonBlankLabelAndDescriptionForTheCollectionCreationUi() {
        for (ModelOption model : EmbeddingModelCatalog.all()) {
            assertFalse(model.label() == null || model.label().isBlank(), model.key() + " has a blank label");
            assertFalse(model.description() == null || model.description().isBlank(), model.key() + " has a blank description");
        }
    }
}
