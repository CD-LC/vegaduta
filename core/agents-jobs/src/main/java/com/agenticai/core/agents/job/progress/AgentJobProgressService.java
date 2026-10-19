package com.agenticai.core.agents.job.progress;

import com.agenticai.core.agents.job.AgentJobProperties;
import com.agenticai.core.agents.job.AgentJobRepository;
import com.agenticai.core.agents.job.dto.AgentJobProgressNoteView;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Writes and reads a job's progress trail.
 *
 * <h2>Writing is BEST-EFFORT BY CONTRACT: {@link #record} never throws</h2>
 * This is the single most important property of this class and the reason its
 * body is one big try/catch. A progress note is commentary; the job is the work.
 * A failed insert - a connection blip, a tenant schema being dropped mid-run, a
 * value that somehow overflows a column - must not be able to fail a job that
 * has been running for twenty minutes and is otherwise about to succeed. That
 * would be a monitoring feature causing the outage it exists to make visible.
 * {@code LoggingToolCallback} takes exactly this posture for its own log write,
 * for exactly this reason.
 *
 * <p>The corollary is that callers get no error to handle and must not invent
 * one. {@link #tryRecord} exists for the one caller that genuinely needs to know
 * whether the note landed - the {@code report_progress} tool, which has to tell
 * the MODEL something truthful - and it reports "did this land", not "did this
 * throw".
 *
 * <h2>Three guards, each against a different real failure</h2>
 * <ul>
 *   <li><b>Truncation</b> ({@code max-note-chars}, 500): matched to the column
 *       width in BOTH tables. A model asked for a short status update will
 *       occasionally return three paragraphs.</li>
 *   <li><b>Per-job cap</b> ({@code max-notes-per-job}, 200): a job stuck in a
 *       loop calling {@code report_progress} must not be able to write unbounded
 *       rows into a tenant's schema. A storage guard, not a rate limit.</li>
 *   <li><b>Dedupe window</b> ({@code dedupe-window-seconds}, 30): models
 *       re-announce the same step. Identical consecutive notes inside the window
 *       are dropped, because a trail reading "Fetching invoices" fifteen times
 *       is strictly worse at answering "is this alive?" than one entry with a
 *       timestamp - it looks like activity while conveying none.</li>
 * </ul>
 *
 * <h2>Two writes per note, deliberately</h2>
 * The row goes into {@code agent_job_progress} (the history) AND the same text
 * is copied onto {@code agent_job.progress_note} (the denormalized latest). The
 * duplication buys the operator list: rendering "what is each of these fifty
 * jobs doing right now" is one query against {@code agent_job} instead of fifty
 * correlated subqueries. The copy uses {@code
 * AgentJobRepository#updateProgressNote}, a targeted single-column UPDATE and
 * emphatically not {@code save(job)} - a full save from here would write back a
 * worker's stale in-memory copy and could silently clear a {@code
 * cancel_requested} an operator set during the turn.
 */
@Service
public class AgentJobProgressService implements JobProgressRecorder {

    private static final Logger log = LoggerFactory.getLogger(AgentJobProgressService.class);

    private final AgentJobProgressNoteRepository noteRepository;
    private final AgentJobRepository jobRepository;
    private final AgentJobProperties properties;

    public AgentJobProgressService(
        AgentJobProgressNoteRepository noteRepository,
        AgentJobRepository jobRepository,
        AgentJobProperties properties
    ) {
        this.noteRepository = noteRepository;
        this.jobRepository = jobRepository;
        this.properties = properties;
    }

    /**
     * Records a note, or silently does nothing. Never throws - see the class
     * javadoc.
     */
    @Override
    public void record(UUID jobId, String note, Integer percent, ProgressSource source) {
        tryRecord(jobId, note, percent, source);
    }

    /**
     * Same write, but says whether it landed.
     *
     * <p>Exists for {@link ReportProgressToolCallback} alone. That caller has to
     * put something in front of the MODEL, and telling a model {@code
     * {"recorded": true}} when the note was silently dropped at the per-job cap
     * teaches it that its calls are working and encourages more of them - the
     * precise behaviour the cap exists to stop. Every other caller should use
     * {@link #record} and ignore the outcome.
     *
     * <p>Still never throws. {@code false} means "not recorded", for any reason,
     * including an infrastructure failure - the distinction between "capped" and
     * "the database was unreachable" is a server-log concern, not something to
     * explain to a language model mid-turn.
     */
    public boolean tryRecord(UUID jobId, String note, Integer percent, ProgressSource source) {
        try {
            String trimmed = normalize(note);
            if (trimmed == null) {
                return false;
            }
            if (noteRepository.countByJobId(jobId) >= properties.getProgress().getMaxNotesPerJob()) {
                log.debug("Agent job {} has reached its progress-note cap; dropping note", jobId);
                return false;
            }
            if (isDuplicateWithinWindow(jobId, trimmed)) {
                return false;
            }

            Instant now = Instant.now();
            noteRepository.save(AgentJobProgressNote.of(
                UUID.randomUUID(), jobId, trimmed, clampPercent(percent), source, now));
            // Best-effort in its own right: the history row is the record, and a
            // failure to refresh the denormalized copy must not undo it.
            try {
                jobRepository.updateProgressNote(jobId, trimmed, now);
            } catch (RuntimeException denormFailed) {
                log.debug("Could not refresh agent_job.progress_note for job {}", jobId, denormFailed);
            }
            return true;

        } catch (RuntimeException e) {
            // Deliberately WARN, not ERROR: nothing is broken for the user, the
            // job carries on, and an ERROR here would page someone about a
            // missing status line.
            log.warn("Could not record a progress note for agent job {} - the job is unaffected", jobId, e);
            return false;
        }
    }

    @Transactional(readOnly = true)
    public List<AgentJobProgressNoteView> notesFor(UUID jobId) {
        return noteRepository.findByJobIdOrderByCreatedAtAscIdAsc(jobId).stream()
            .map(AgentJobProgressNoteView::from)
            .toList();
    }

    /**
     * The incremental read behind the live trace stream. {@code after} is
     * EXCLUSIVE, so a client polling with the timestamp of the last note it saw
     * never receives it twice.
     */
    @Transactional(readOnly = true)
    public List<AgentJobProgressNoteView> notesAfter(UUID jobId, Instant after) {
        if (after == null) {
            return notesFor(jobId);
        }
        return noteRepository.findByJobIdAndCreatedAtAfterOrderByCreatedAtAscIdAsc(jobId, after).stream()
            .map(AgentJobProgressNoteView::from)
            .toList();
    }

    // --------------------------------------------------------------- internals

    /** Null/blank in, null out. Otherwise trimmed and cut to the configured width. */
    private String normalize(String note) {
        if (note == null || note.isBlank()) {
            return null;
        }
        String trimmed = note.strip();
        int max = properties.getProgress().getMaxNoteChars();
        return trimmed.length() <= max ? trimmed : trimmed.substring(0, max);
    }

    /**
     * Compares against the newest ROW, not against the worker's loaded copy of
     * {@code agent_job.progress_note}: that copy is stale by construction (loaded
     * before a turn that may have run for minutes), and notes are written from
     * tool calls running on Reactor threads that never saw it at all.
     */
    private boolean isDuplicateWithinWindow(UUID jobId, String note) {
        int windowSeconds = properties.getProgress().getDedupeWindowSeconds();
        if (windowSeconds <= 0) {
            return false;
        }
        Optional<AgentJobProgressNote> latest = noteRepository.findFirstByJobIdOrderByCreatedAtDescIdDesc(jobId);
        if (latest.isEmpty() || !note.equals(latest.get().getNote())) {
            return false;
        }
        Instant cutoff = Instant.now().minus(Duration.ofSeconds(windowSeconds));
        return latest.get().getCreatedAt().isAfter(cutoff);
    }

    /**
     * Clamps rather than rejects, and only ever narrows. A model that says "110%
     * done" has made a formatting mistake, not a request worth failing its tool
     * call over; the note itself is the valuable part and 100 is the honest
     * reading of the intent. Null stays null - see {@link
     * AgentJobProgressNote#getPercent}.
     */
    private static Short clampPercent(Integer percent) {
        if (percent == null) {
            return null;
        }
        return (short) Math.max(0, Math.min(100, percent));
    }
}
