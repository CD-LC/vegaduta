package com.agenticai.core.knowledge;

import com.agenticai.core.knowledge.dto.KnowledgeIngestionJobResponse;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Knowledge Stage F3c - status polling for async ingestion jobs (audio
 * today; Stage F3d's video ingestion will reuse this same job/polling
 * mechanism rather than adding a second one).
 */
@RestController
@RequestMapping("/api/knowledge/jobs")
@Profile("knowledge")
public class KnowledgeIngestionJobController {

    private final KnowledgeIngestionJobService jobService;
    private final KnowledgeAccessService access;

    public KnowledgeIngestionJobController(
            KnowledgeIngestionJobService jobService, KnowledgeAccessService access) {
        this.jobService = jobService;
        this.access = access;
    }

    /** A job reveals the file name and target collection, so it needs read access to that collection. */
    @GetMapping("/{jobId}")
    public KnowledgeIngestionJobResponse get(@PathVariable UUID jobId) {
        KnowledgeIngestionJobResponse job = jobService.get(jobId);
        if (job.collectionId() != null) {
            access.requireRead(job.collectionId());
        }
        return job;
    }
}
