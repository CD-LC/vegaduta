package com.agenticai.core.knowledge;

import static org.assertj.core.api.Assertions.assertThat;

import com.agenticai.core.knowledge.dto.KnowledgeCollectionCreateRequest;
import com.agenticai.core.knowledge.dto.KnowledgeCollectionUpdateRequest;
import com.agenticai.core.knowledge.dto.SearchRequest;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;

class KnowledgeRequestValidationTest {

    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    @Test
    void aCollectionNameOverTheColumnWidthIsRejected() {
        assertThat(validator.validate(new KnowledgeCollectionCreateRequest("x".repeat(500), null, null, null, null)))
            .hasSize(1);
        assertThat(validator.validate(new KnowledgeCollectionCreateRequest("x".repeat(255), null, null, null, null)))
            .isEmpty();
        assertThat(validator.validate(new KnowledgeCollectionUpdateRequest("x".repeat(256), null, null))).hasSize(1);
    }

    @Test
    void chunkingBoundsAreEnforced() {
        assertThat(validator.validate(new KnowledgeCollectionCreateRequest("a", null, null, null, null, 5, 0)))
            .hasSize(1);
        assertThat(validator.validate(new KnowledgeCollectionCreateRequest("a", null, null, null, null, 201, 0)))
            .hasSize(1);
        assertThat(validator.validate(new KnowledgeCollectionCreateRequest("a", null, null, null, null, 150, -1)))
            .hasSize(1);
        assertThat(validator.validate(new KnowledgeCollectionUpdateRequest("a", null, null, 150, 30))).isEmpty();
    }

    @Test
    void topKIsBounded() {
        assertThat(validator.validate(new SearchRequest("q", 100000, null))).hasSize(1);
        assertThat(validator.validate(new SearchRequest("q", 0, null))).hasSize(1);
        assertThat(validator.validate(new SearchRequest("q", 10, null))).isEmpty();
    }

    @Test
    void theDuplicateUploadMessageNamesTheFileOnly() {
        String message = new KnowledgeDuplicateDocumentException("report.pdf").getMessage();
        assertThat(message).contains("report.pdf").doesNotContain("/api/").doesNotContain("document ");
    }
}
