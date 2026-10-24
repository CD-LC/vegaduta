package com.agenticai.core.agents.turn;

import com.agenticai.core.chat.PromptBlockProvenance;
import com.agenticai.core.prompts.PromptSource;
import com.agenticai.core.prompts.ResolvedPrompt;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Hands the request inspector the provenance of the standing system text a turn was just assembled from: for each
 * block the prompt-registry key, the scope that supplied the wording and its version (or just the section name for
 * text that is not a registry prompt). The inspector labels the system messages of the model calls that follow.
 * Display-only and best-effort: a failure here is logged at debug and never touches the turn.
 */
final class PromptBlockProvenanceRecorder {

    private static final Logger log = LoggerFactory.getLogger(PromptBlockProvenanceRecorder.class);

    private PromptBlockProvenanceRecorder() {}

    static void record(String sessionId, ChatSystemPromptComposer.Composed composed) {
        try {
            if (sessionId == null || composed == null) {
                return;
            }
            List<PromptBlockProvenance.Block> blocks = new ArrayList<>();
            addAll(blocks, composed.systemBlocks());
            addAll(blocks, composed.memoryBlocks());
            PromptBlockProvenance.record(sessionId, blocks);
        } catch (RuntimeException e) {
            log.debug("Prompt block provenance not recorded: {}", e.toString());
        }
    }

    private static void addAll(List<PromptBlockProvenance.Block> out, List<ChatSystemPromptComposer.Block> blocks) {
        for (ChatSystemPromptComposer.Block b : blocks) {
            ResolvedPrompt p = b.prompt();
            if (p == null) {
                // Same labels as PromptPreviewService, so the preview and the inspector agree.
                String source = b.id().startsWith("agent.") ? "agent_config" : "code";
                out.add(new PromptBlockProvenance.Block(b.id(), null, null, source, 0, "", b.text(), 0));
            } else {
                out.add(new PromptBlockProvenance.Block(
                        b.id(),
                        p.key(),
                        p.scope() == null ? null : p.scope().dbValue(),
                        p.source().name().toLowerCase(Locale.ROOT),
                        p.source() == PromptSource.DEFAULT || p.source() == PromptSource.FALLBACK ? 0 : p.version(),
                        p.hash(),
                        b.text(),
                        p.estimatedTokens()));
            }
        }
    }
}
