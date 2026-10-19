package com.agenticai.core.knowledge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.EnumSet;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The re-embed state machine ({@link KnowledgeReembedJobStatus} +
 * {@link KnowledgeReembedJob}'s guarded transitions). The resume path depends
 * on EMBEDDING -> EMBEDDING and SWEEPING -> SWEEPING being legal; the
 * "never complete without flipping" guarantee depends on EMBEDDING ->
 * COMPLETED being illegal.
 */
class KnowledgeReembedJobTest {

    private static KnowledgeReembedJob newJob() {
        return new KnowledgeReembedJob(UUID.randomUUID(), "minilm-l6-v2", "multilingual-minilm", 10);
    }

    @Test
    void newJobIsQueuedWithZeroProgress() {
        KnowledgeReembedJob job = newJob();
        assertEquals(KnowledgeReembedJobStatus.QUEUED, job.getStatus());
        assertEquals(0, job.getProcessedChunks());
        assertEquals(10, job.getTotalChunks());
    }

    @Test
    void happyPathWalksQueuedEmbeddingSweepingCompleted() {
        KnowledgeReembedJob job = newJob();
        job.markEmbedding();
        job.markEmbedding(); // resume re-entry
        job.markSweeping();
        job.markSweeping(); // resume re-entry
        job.markCompleted();
        assertEquals(KnowledgeReembedJobStatus.COMPLETED, job.getStatus());
        assertEquals(10, job.getProcessedChunks(), "completion reports full progress");
        assertNotNull(job.getCompletedAt());
    }

    @Test
    void cannotCompleteWithoutPassingThroughTheFlipIntoSweeping() {
        KnowledgeReembedJob job = newJob();
        assertThrows(IllegalStateException.class, job::markCompleted);
        job.markEmbedding();
        assertThrows(IllegalStateException.class, job::markCompleted);
        assertThrows(IllegalStateException.class, newJob()::markSweeping, "QUEUED cannot skip embedding");
    }

    @Test
    void sweepingCannotGoBackToEmbedding() {
        KnowledgeReembedJob job = newJob();
        job.markEmbedding();
        job.markSweeping();
        assertThrows(IllegalStateException.class, job::markEmbedding);
    }

    @Test
    void anyNonTerminalStateCanFailAndTerminalStatesAreFinal() {
        for (int stage = 0; stage < 3; stage++) {
            KnowledgeReembedJob job = newJob();
            if (stage >= 1) {
                job.markEmbedding();
            }
            if (stage >= 2) {
                job.markSweeping();
            }
            job.markFailed("sidecar down");
            assertEquals(KnowledgeReembedJobStatus.FAILED, job.getStatus());
            assertEquals("sidecar down", job.getErrorMessage());
            assertThrows(IllegalStateException.class, job::markEmbedding);
            assertThrows(IllegalStateException.class, () -> job.markFailed("again"));
        }
    }

    @Test
    void longErrorMessagesAreTruncated() {
        KnowledgeReembedJob job = newJob();
        job.markFailed("x".repeat(5000));
        assertEquals(2000, job.getErrorMessage().length());
    }

    @Test
    void progressIsClampedToTheTotal() {
        KnowledgeReembedJob job = newJob();
        job.updateProgress(15, 12);
        assertEquals(12, job.getTotalChunks());
        assertEquals(12, job.getProcessedChunks());
        job.updateProgress(-3, 12);
        assertEquals(0, job.getProcessedChunks());
    }

    @Test
    void activeSetMatchesTheNonTerminalStatesAndTheV362IndexPredicate() {
        assertEquals(EnumSet.of(KnowledgeReembedJobStatus.QUEUED, KnowledgeReembedJobStatus.EMBEDDING, KnowledgeReembedJobStatus.SWEEPING),
            KnowledgeReembedJobStatus.ACTIVE);
        for (KnowledgeReembedJobStatus s : KnowledgeReembedJobStatus.values()) {
            assertEquals(!KnowledgeReembedJobStatus.ACTIVE.contains(s), s.isTerminal());
        }
        assertFalse(KnowledgeReembedJobStatus.COMPLETED.canTransitionTo(KnowledgeReembedJobStatus.FAILED));
        assertTrue(KnowledgeReembedJobStatus.QUEUED.canTransitionTo(KnowledgeReembedJobStatus.FAILED));
    }
}
