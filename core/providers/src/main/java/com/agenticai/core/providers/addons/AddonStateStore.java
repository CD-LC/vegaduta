package com.agenticai.core.providers.addons;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * The only writer of {@code control_plane.addon_state}. Every public method is one transaction, so a
 * change that touches several add-ons (turning one on also turns its requirements on) lands whole or
 * not at all. Rows carry an optimistic-lock version: two writers racing on one add-on cannot both
 * win, the loser sees an {@code ObjectOptimisticLockingFailureException} which the API answers with
 * 409 {@code conflict}.
 */
@Component
public class AddonStateStore {

    /** Values a host may report. */
    static final Set<String> REPORTABLE = Set.of(
            AddonState.RUNNING, AddonState.STOPPED, AddonState.APPLYING, AddonState.FAILED);

    static final int MAX_MESSAGE = 500;

    /**
     * One wish to record. {@code effectiveNow} is what core currently takes the wish to be (see {@link
     * AddonStateRules}). {@code retry} records the same wish again as a new one, so a host that failed
     * to apply it tries again.
     */
    record Step(String id, boolean desired, boolean effectiveNow, boolean retry) {}

    record Applied(String id, boolean desired, long rev) {}

    /** One host report, already validated for shape by the service. */
    record Report(String id, long rev, String status, String message) {}

    record Ignored(String id, String reason) {}

    record ReportOutcome(int accepted, List<Ignored> ignored) {}

    private final AddonStateRepository repository;

    public AddonStateStore(AddonStateRepository repository) {
        this.repository = repository;
    }

    @Transactional(readOnly = true)
    public Map<String, AddonState> all() {
        Map<String, AddonState> out = new HashMap<>();
        for (AddonState row : repository.findAll()) {
            out.put(row.getAddonId(), row);
        }
        return out;
    }

    /** Records the wishes in order; a step that changes nothing writes nothing. Returns what changed. */
    @Transactional
    public List<Applied> setDesired(List<Step> steps, String actor) {
        List<Applied> changed = new ArrayList<>();
        for (Step step : steps) {
            AddonState row = repository.findById(step.id()).orElse(null);
            if (row == null) {
                if (step.desired() == step.effectiveNow()) {
                    continue;
                }
                row = repository.save(new AddonState(step.id(), step.desired(), 1, actor));
            } else if (row.isDesired() == step.desired() && !step.retry()) {
                continue;
            } else {
                row.change(step.desired(), actor);
                row = repository.save(row);
            }
            changed.add(new Applied(row.getAddonId(), row.isDesired(), row.getRev()));
        }
        return changed;
    }

    /**
     * Stores what the host says it did. A report about a wish that does not exist yet ({@code rev}
     * ahead of ours) or older than one already stored is ignored and named in the answer; the host
     * simply reports again on its next pass.
     *
     * @param effectiveNow per add-on, what core takes the wish to be right now; used only to create
     *     a row for an add-on nobody has touched, so that the first report never flips the wish
     */
    @Transactional
    public ReportOutcome record(List<Report> reports, Map<String, Boolean> effectiveNow) {
        int accepted = 0;
        List<Ignored> ignored = new ArrayList<>();
        for (Report report : reports) {
            AddonState row = repository.findById(report.id()).orElse(null);
            if (row == null) {
                if (report.rev() != 0) {
                    ignored.add(new Ignored(report.id(), "rev_ahead"));
                    continue;
                }
                row = new AddonState(report.id(), Boolean.TRUE.equals(effectiveNow.get(report.id())), 0, null);
            } else if (report.rev() > row.getRev()) {
                ignored.add(new Ignored(report.id(), "rev_ahead"));
                continue;
            } else if (row.getReportedRev() != null && report.rev() < row.getReportedRev()) {
                ignored.add(new Ignored(report.id(), "stale"));
                continue;
            }
            row.report(report.status(), report.rev(), clean(report.message()));
            repository.save(row);
            accepted++;
        }
        return new ReportOutcome(accepted, ignored);
    }

    /** One line, no control characters, bounded; a host's free text is data, never markup or a log forgery. */
    static String clean(String message) {
        if (message == null) {
            return null;
        }
        String line = message.replaceAll("\\p{Cntrl}", " ").strip();
        if (line.isEmpty()) {
            return null;
        }
        return line.length() > MAX_MESSAGE ? line.substring(0, MAX_MESSAGE) : line;
    }
}
