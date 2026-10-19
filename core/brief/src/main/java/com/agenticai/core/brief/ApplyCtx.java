package com.agenticai.core.brief;

import com.agenticai.core.brief.BriefApplyLedger.Binding;
import com.agenticai.core.brief.dto.ReadinessRow;
import com.agenticai.core.brief.model.Brief;
import com.agenticai.core.workflow.dto.WorkflowGraphDto;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Per-apply working state shared by {@link BriefApplyService} and {@link BriefApplyPhases}. */
final class ApplyCtx {

    record Undo(UUID stepId, String ref, boolean created, Runnable action) {
    }

    static final class StepFailure extends RuntimeException {
        final int seq;
        final String kind;
        final String ref;

        StepFailure(int seq, String kind, String ref, Throwable cause) {
            super(BriefApplyService.messageOf(cause), cause);
            this.seq = seq;
            this.kind = kind;
            this.ref = ref;
        }
    }

        final UUID briefId;
        final int version;
        final UUID jobId;
        final Brief brief;
        final Brief previous;
        final TrustRung rung;
        final Map<String, Binding> bindings = new LinkedHashMap<>();
        final Deque<Undo> undo = new ArrayDeque<>();
        final Set<String> createdRefs = new LinkedHashSet<>();
        final Map<String, String> skipped = new LinkedHashMap<>();
        final Map<String, List<String>> mcpTools = new LinkedHashMap<>();
        final Map<String, WorkflowGraphDto> workflowGraphs = new LinkedHashMap<>();
        Map<String, ReadinessRow> integrationRows;
        boolean canIntegrate;
        boolean canTools;
        boolean canChannels;
        int seq;

        ApplyCtx(UUID briefId, int version, UUID jobId, Brief brief, Brief previous, TrustRung rung) {
            this.briefId = briefId;
            this.version = version;
            this.jobId = jobId;
            this.brief = brief;
            this.previous = previous;
            this.rung = rung;
        }
    
}
