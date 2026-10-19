package com.agenticai.core.quality.dataset;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * 409 for run-lifecycle conflicts: triggering a dataset that already has a
 * live run (backed by the {@code uq_eval_run_live_per_dataset} partial
 * unique index - the DATABASE is the arbiter, this exception just narrates
 * its decision), cancelling an already-terminal run, or deleting a dataset
 * mid-run. See {@link EvalDatasetNotFoundException} for the
 * {@code @ResponseStatus}-over-central-handler choice.
 */
@ResponseStatus(HttpStatus.CONFLICT)
public class EvalRunConflictException extends RuntimeException {

    public EvalRunConflictException(String message) {
        super(message);
    }
}
