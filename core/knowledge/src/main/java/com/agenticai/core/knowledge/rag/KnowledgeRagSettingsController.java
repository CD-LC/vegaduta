package com.agenticai.core.knowledge.rag;

import com.agenticai.core.knowledge.rag.RagDtos.RagSettingsResponse;
import com.agenticai.core.knowledge.rag.RagDtos.RagSettingsUpdateRequest;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** A collection's retrieval-quality settings (parent-child opt-in). Thin: rules live in {@link KnowledgeRagSettingsService}. */
@RestController
@RequestMapping("/api/knowledge/collections/{collectionId}/retrieval-settings")
@Profile("knowledge")
public class KnowledgeRagSettingsController {

    private final KnowledgeRagSettingsService settingsService;

    public KnowledgeRagSettingsController(KnowledgeRagSettingsService settingsService) {
        this.settingsService = settingsService;
    }

    @GetMapping
    public RagSettingsResponse get(@PathVariable UUID collectionId) {
        return settingsService.get(collectionId);
    }

    @PutMapping
    public RagSettingsResponse update(@PathVariable UUID collectionId, @RequestBody RagSettingsUpdateRequest request) {
        return settingsService.update(collectionId, request);
    }
}
