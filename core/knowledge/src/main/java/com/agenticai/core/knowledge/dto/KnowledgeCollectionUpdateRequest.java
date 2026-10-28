package com.agenticai.core.knowledge.dto;

import com.agenticai.core.knowledge.KnowledgeVisibility;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.UUID;

public record KnowledgeCollectionUpdateRequest(
    @NotBlank @Size(max = 255) String name,
    String description,
    UUID parentCollectionId,
    // Migration V361 - null leaves the stored value unchanged (unlike the
    // three fields above, which are replaced). Affects future ingestion only.
    @Min(10) @Max(200) Integer chunkSize,
    @Min(0) @Max(199) Integer chunkOverlap,
    // Migration V710 - null leaves the visibility unchanged. Changing it needs the owner or an
    // administrator (an administrator only, for a legacy collection that has no owner).
    KnowledgeVisibility visibility
) {
    /** Pre-V710 shape (chunking, no visibility) - kept so existing callers compile unchanged. */
    public KnowledgeCollectionUpdateRequest(
        String name, String description, UUID parentCollectionId, Integer chunkSize, Integer chunkOverlap
    ) {
        this(name, description, parentCollectionId, chunkSize, chunkOverlap, null);
    }

    /** Pre-V361 shape - kept so existing callers compile unchanged. */
    public KnowledgeCollectionUpdateRequest(String name, String description, UUID parentCollectionId) {
        this(name, description, parentCollectionId, null, null, null);
    }
}
