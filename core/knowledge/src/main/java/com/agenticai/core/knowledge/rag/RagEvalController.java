package com.agenticai.core.knowledge.rag;

import com.agenticai.core.knowledge.rag.RagDtos.RagEvalRequest;
import com.agenticai.core.knowledge.rag.RagDtos.RagEvalResponse;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code POST /api/knowledge/{collectionId}/eval} - run a retrieval eval dataset against a collection
 * under several retrieval configurations. Thin on purpose: the plan gate, validation and caps are all
 * decided in {@link RagEvalService}.
 */
@RestController
@RequestMapping("/api/knowledge/{collectionId}/eval")
@Profile("knowledge")
public class RagEvalController {

    private final RagEvalService evalService;

    public RagEvalController(RagEvalService evalService) {
        this.evalService = evalService;
    }

    @PostMapping
    public RagEvalResponse run(@PathVariable UUID collectionId, @RequestBody RagEvalRequest request) {
        return evalService.run(collectionId, request);
    }
}
