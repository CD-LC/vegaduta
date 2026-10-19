package com.agenticai.core.providers.addons;

import com.agenticai.core.providers.addons.AddonDefinition.Kind;

/**
 * The state machine, as one pure function: given the catalog entry and everything known about it,
 * what does a person want and what is actually going on. No I/O, so every branch is unit-tested.
 *
 * <p><b>The wish.</b> A stored row decides. With no row, a compose add-on is wanted when the
 * catalog says {@code defaultOn} or when core can see it running (the person installed it before
 * this registry existed, and the host must not switch it off just because nobody clicked), and a
 * model is wanted when Ollama already has it.
 *
 * <p><b>The status.</b> For a compose add-on only a host report about the <em>current</em> wish
 * counts; an older report is history. Core's own observation ({@link AddonDefinition.Observe})
 * stands in for a report when there is none.
 */
final class AddonStateRules {

    private AddonStateRules() {}

    /** A model download as {@link com.agenticai.core.providers.localmodels.LocalModelPullService} reports it. */
    record Pull(String state, int percent, String message) {
        boolean active() {
            return "pending".equals(state) || "running".equals(state);
        }

        boolean failed() {
            return "failed".equals(state);
        }
    }

    /**
     * @param row the stored state, or null
     * @param observed whether core sees the add-on running; null when it cannot tell
     * @param installed whether the model is in Ollama; null when Ollama cannot be reached
     * @param pull the latest download of this model, or null
     */
    record Facts(AddonState row, Boolean observed, Boolean installed, Pull pull) {}

    record Result(boolean desired, AddonStatus status, String message, Integer progress) {}

    static Result evaluate(AddonDefinition d, Facts f) {
        if (!d.isAvailable()) {
            return new Result(false, AddonStatus.UNAVAILABLE, null, null);
        }
        return d.kind() == Kind.MODEL_PULL ? model(f) : compose(d, f);
    }

    /** The wish alone, for callers that only need to know who is on (dependency checks). */
    static boolean desired(AddonDefinition d, Facts f) {
        return evaluate(d, f).desired();
    }

    private static Result compose(AddonDefinition d, Facts f) {
        AddonState row = f.row();
        boolean desired = row != null ? row.isDesired() : d.defaultOn() || Boolean.TRUE.equals(f.observed());
        String report = row != null && row.reportIsCurrent() ? row.getReportedStatus() : null;
        String message = report != null ? row.getReportedMessage() : null;
        boolean wasRunning = row != null && !row.reportIsCurrent() && AddonState.RUNNING.equals(row.getReportedStatus());
        boolean seenRunning = Boolean.TRUE.equals(f.observed());
        if (AddonState.FAILED.equals(report)) {
            return new Result(desired, AddonStatus.FAILED, message, null);
        }
        if (AddonState.APPLYING.equals(report)) {
            return new Result(desired, AddonStatus.APPLYING, message, null);
        }
        if (desired) {
            boolean on = AddonState.RUNNING.equals(report) || (report == null && seenRunning);
            return new Result(desired, on ? AddonStatus.ON : AddonStatus.ENABLE_REQUESTED, message, null);
        }
        boolean stillRunning = AddonState.RUNNING.equals(report) || (report == null && (wasRunning || seenRunning));
        return new Result(desired, stillRunning ? AddonStatus.DISABLE_REQUESTED : AddonStatus.OFF, message, null);
    }

    private static Result model(Facts f) {
        AddonState row = f.row();
        boolean installed = Boolean.TRUE.equals(f.installed());
        boolean desired = row != null ? row.isDesired() : installed;
        Pull pull = f.pull();
        if (pull != null && pull.active()) {
            return new Result(desired, AddonStatus.PULLING, pull.message(), pull.percent());
        }
        if (!desired) {
            return new Result(false, AddonStatus.OFF, null, null);
        }
        if (installed) {
            return new Result(true, AddonStatus.ON, null, null);
        }
        if (f.installed() == null) {
            return new Result(true, AddonStatus.ENABLE_REQUESTED, "The model server is not reachable, so the download has not started.", null);
        }
        if (pull != null && pull.failed()) {
            return new Result(true, AddonStatus.FAILED, pull.message(), null);
        }
        return new Result(true, AddonStatus.FAILED, "The model is not installed. Turn it on again to download it.", null);
    }
}
