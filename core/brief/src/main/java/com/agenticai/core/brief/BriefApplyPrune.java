package com.agenticai.core.brief;

import com.agenticai.core.brief.BriefApplyLedger.Binding;
import com.agenticai.core.brief.model.Brief;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static com.agenticai.core.brief.BriefApplyService.messageOf;

/**
 * The last phase of an apply: removes what the brief no longer declares (or, for a purge, everything), in reverse
 * dependency order. Split out of {@link BriefApplyPhases}, which creates and updates; this class only deletes, each
 * removal under the same per-kind permission its creation needed.
 */
final class BriefApplyPrune {

    private static final Logger log = LoggerFactory.getLogger(BriefApplyPrune.class);

    private final EnvoyPorts ports;
    private final BriefApplyLedger ledger;

    BriefApplyPrune(EnvoyPorts ports, BriefApplyLedger ledger) {
        this.ports = ports;
        this.ledger = ledger;
    }

    /** Removes what the brief no longer declares (or, for a purge, everything), reverse dependency order. */
    void pruneOrphans(ApplyCtx c, Set<String> declared) {
        List<String> order = List.of("EVAL_DATASET", "CHANNEL", "WORKFLOW", "AGENT", "MCP", "TOOL_OPENAPI", "TOOL_CODE",
            "PROCEDURE", "KNOWLEDGE");
        for (String kind : order) {
            for (Map.Entry<String, Binding> e : new ArrayList<>(c.bindings.entrySet())) {
                if (!e.getValue().kind().equals(kind) || declared.contains(e.getKey())) {
                    continue;
                }
                String ref = e.getKey();
                Binding b = e.getValue();
                UUID stepId = ledger.begin(c.briefId, c.version, c.jobId, ++c.seq, "PRUNE_" + kind, ref);
                try {
                    deleteEntity(c, ref, b);
                    ledger.unbind(c.briefId, ref);
                    c.bindings.remove(ref);
                    ledger.done(stepId, b.entityId(), null);
                } catch (RuntimeException ex) {
                    // Not fatal: the envoy is already correct; the leftover stays bound so a later apply or purge retries.
                    ledger.failed(stepId, messageOf(ex));
                    log.warn("Brief {}: could not remove {} {}: {}", c.briefId, kind, b.entityId(), ex.getMessage());
                }
            }
        }
    }

    private void deleteEntity(ApplyCtx c, String ref, Binding b) {
        // Removal needs the same per-kind permission as creation: the catch-all DELETE only asks for Manage agents.
        switch (b.kind()) {
            case "CHANNEL" -> requireHeld(c.canChannels, "Manage channels", b);
            case "MCP" -> requireHeld(c.canIntegrate, "Manage integrations", b);
            case "TOOL_OPENAPI", "TOOL_CODE" -> requireHeld(c.canTools && c.canIntegrate, "Manage tools and Manage integrations", b);
            default -> { }
        }
        switch (b.kind()) {
            case "EVAL_DATASET" -> ports.deleteEvalDataset(b.entityId());
            case "CHANNEL" -> {
                String agent = channelAgent(c, ref);
                if (agent != null) {
                    ports.unbindChannel(b.entityId(), agent);
                }
            }
            case "WORKFLOW" -> ports.deleteWorkflow(b.entityId());
            case "AGENT" -> ports.deleteAgent(b.entityId());
            case "MCP" -> ports.deleteMcp(b.entityId());
            case "TOOL_OPENAPI", "TOOL_CODE" -> ports.deleteTool(b.kind(), b.entityId());
            case "PROCEDURE" -> ports.deleteProcedure(b.entityId());
            case "KNOWLEDGE" -> ports.deleteKnowledge(b.entityId());
            default -> throw new IllegalStateException("Unknown binding kind " + b.kind());
        }
    }

    private static void requireHeld(boolean held, String permission, Binding b) {
        if (!held) {
            throw new IllegalStateException("Removing the " + b.kind().toLowerCase(Locale.ROOT) + " needs the " + permission
                + " permission; it was left in place.");
        }
    }

    /** The real agent id a channel binding routes to, from the brief that declared it (now or before); null when unknown. */
    private static String channelAgent(ApplyCtx c, String ref) {
        for (Brief source : new Brief[] {c.brief, c.previous}) {
            if (source == null) {
                continue;
            }
            Brief.ChannelSpec spec = BriefApplyPhases.find(source.channels(), Brief.ChannelSpec::ref, ref);
            Binding target = spec == null || spec.bindTo() == null ? null : c.bindings.get(spec.bindTo());
            if (target != null) {
                return target.entityId();
            }
        }
        return null;
    }
}
