package com.agenticai.core.brief;

import com.agenticai.core.brief.BriefApplyLedger.Binding;
import com.agenticai.core.brief.EnvoyPorts.ApplySkipException;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Runs one apply step as a ledger-recorded unit: begin, body, done / skipped / failed. Hands the body a
 * {@link Handle} to register what it created and how to undo it.
 */
final class ApplyStepRunner {

    private static final Logger log = LoggerFactory.getLogger(ApplyStepRunner.class);

    private final BriefApplyLedger ledger;

    ApplyStepRunner(BriefApplyLedger ledger) {
        this.ledger = ledger;
    }

    interface Body {
        /** @return an optional note stored on the step (prefixed NOTE:) */
        String run(Handle h);
    }

    void step(ApplyCtx c, String kind, String ref, Body body) {
        int seq = ++c.seq;
        UUID stepId = ledger.begin(c.briefId, c.version, c.jobId, seq, kind, ref);
        try {
            String note = body.run(new Handle(c, stepId, ref));
            ledger.done(stepId, null, note);
        } catch (ApplySkipException skip) {
            ledger.done(stepId, null, BriefApplyService.SKIPPED + skip.getMessage());
            if (ref != null) {
                c.skipped.put(ref, skip.getMessage());
            }
        } catch (RuntimeException e) {
            try {
                ledger.failed(stepId, BriefApplyService.messageOf(e));
            } catch (RuntimeException ledgerFailure) {
                log.error("Could not record the failure of step {}", stepId, ledgerFailure);
            }
            throw new ApplyCtx.StepFailure(seq, kind, ref, e);
        }
    }

    /** What a step body uses to tell the engine what exists (so a failure later can undo it). */
    final class Handle {
        private final ApplyCtx c;
        private final UUID stepId;
        private final String ref;

        Handle(ApplyCtx c, UUID stepId, String ref) {
            this.c = c;
            this.stepId = stepId;
            this.ref = ref;
        }

        /** The real service returned a new entity: log it, bind the ref, and register how to delete it. */
        void created(String bindingKind, String entityId, Runnable delete) {
            ledger.created(stepId, entityId);
            ledger.bind(c.briefId, ref, bindingKind, entityId);
            c.bindings.put(ref, new Binding(bindingKind, entityId));
            c.createdRefs.add(ref);
            c.undo.push(new ApplyCtx.Undo(stepId, ref, true, delete));
        }

        /** Registered BEFORE an in-place update, so a failure part-way through it is also restored. */
        void willUpdate(Runnable restore) {
            c.undo.push(new ApplyCtx.Undo(stepId, ref, false, restore));
        }
    }
}
