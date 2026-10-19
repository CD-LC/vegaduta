package com.agenticai.core.agents.turn;

import com.agenticai.core.agents.core.AgentService;
import com.agenticai.core.agents.memory.AgentMemoryEntry;
import com.agenticai.core.agents.memory.AgentMemoryService;
import com.agenticai.core.agents.memory.MemoryBlocks;
import com.agenticai.core.agents.memory.QueryEmbedding;
import com.agenticai.core.agents.memory.TenantMemoryEntry;
import com.agenticai.core.agents.memory.TenantMemoryService;
import com.agenticai.core.agents.tools.AgentStepEventEmitter;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;

/**
 * Long-term (private + tenant-shared) memory recall block for a chat turn, extracted verbatim from
 * {@link AgentChatService} (extract-only split).
 */
final class ChatMemoryRecall {

    private static final Logger log = LoggerFactory.getLogger(AgentChatService.class);

    private final ObjectProvider<AgentMemoryService> agentMemoryService;
    private final ObjectProvider<TenantMemoryService> tenantMemoryService;
    private final AgentService agentService;

    /**
     * {@code app.chat.sse.memory-events.enabled}, owned by {@link AgentChatService}; a supplier
     * because it is field-injected.
     */
    private final BooleanSupplier memoryEventsEnabled;

    ChatMemoryRecall(
            ObjectProvider<AgentMemoryService> agentMemoryService,
            ObjectProvider<TenantMemoryService> tenantMemoryService,
            AgentService agentService,
            BooleanSupplier memoryEventsEnabled) {
        this.agentMemoryService = agentMemoryService;
        this.tenantMemoryService = tenantMemoryService;
        this.agentService = agentService;
        this.memoryEventsEnabled = memoryEventsEnabled;
    }

    /**
     * Recalled long-term memories, as a standalone "Known facts about this user" block - the model
     * still decides how (or whether) to use them, this just makes sure they're in context without
     * requiring the model to proactively call a recall tool. Deliberately returned as its own text
     * block instead of being concatenated onto the agent's system prompt (see {@link #chat}'s
     * prompt-caching comment for why the split matters for Anthropic cache breakpoints). Returns
     * {@code null} if the "knowledge" profile is off (no {@code AgentMemoryService} bean), recall
     * throws (sidecar unreachable, etc.), or nothing relevant was found - a missing memory feature
     * should never break chat.
     */
    String recallMemoryFactsBlock(UUID agentId, String subjectKey, String userMessage) {
        return recallMemoryFactsBlock(agentId, subjectKey, userMessage, null);
    }

    /**
     * As {@link #recallMemoryFactsBlock(UUID, String, String)}; additionally, on a step-event turn
     * ({@code stepEvents} live and {@code app.chat.sse.memory-events.enabled}), reports once what
     * was injected - block labels, counts and fact ids, never the text. The returned block is the
     * same with or without an emitter, and a {@code null}/disabled emitter takes exactly the old
     * path.
     */
    String recallMemoryFactsBlock(
            UUID agentId, String subjectKey, String userMessage, AgentStepEventEmitter stepEvents) {
        StringBuilder block = new StringBuilder();
        boolean hasUserBlocks = false;
        boolean report = stepEvents != null && stepEvents.isEnabled() && memoryEventsEnabled();
        List<String> usedBlockLabels = List.of();
        int privateFacts = 0;
        int sharedFacts = 0;
        List<UUID> factIds = new ArrayList<>();

        // User-editable always-in-context blocks (persona / user_profile / project_notes), item 11.
        // Empty unless the user wrote some, so simple requests gain no tokens.
        TenantMemoryService blockService = tenantMemoryService.getIfAvailable();
        if (blockService != null) {
            try {
                // Gated on the agent's shareMemoryWithTenant opt-in, like the shared facts below,
                // so
                // persona / profile text never reaches an agent that did not opt into shared
                // memory.
                String blocks;
                if (!agentService.isShareMemoryWithTenant(agentId)) {
                    blocks = null;
                } else if (report) {
                    MemoryBlocks.Rendered rendered =
                            blockService.renderPromptBlocksWithLabels(subjectKey, userMessage);
                    blocks = rendered == null ? null : rendered.text();
                    if (rendered != null && rendered.labels() != null) {
                        usedBlockLabels = rendered.labels();
                    }
                } else {
                    blocks = blockService.renderPromptBlocks(subjectKey, userMessage);
                }
                if (blocks != null) {
                    block.append(blocks).append("\n\n");
                    hasUserBlocks = true;
                }
            } catch (Exception e) {
                log.warn(
                        "Memory block render failed for subject '{}' - continuing without it",
                        subjectKey,
                        e);
            }
        }

        // One embedding of the message for both stores below: the sidecar is asked once, by
        // whichever
        // recall needs it first.
        QueryEmbedding embedding = null;
        AgentMemoryService memoryService = agentMemoryService.getIfAvailable();
        if (memoryService != null) {
            try {
                embedding = memoryService.queryEmbedding(userMessage);
                List<AgentMemoryEntry> memories =
                        memoryService.recall(agentId, subjectKey, userMessage, embedding);
                if (!memories.isEmpty()) {
                    String factList =
                            memories.stream()
                                    .map(m -> "- " + m.content())
                                    .reduce((a, b) -> a + "\n" + b)
                                    .orElse("");
                    block.append(
                                    "Known facts about this user from previous conversations (use"
                                            + " if relevant, ")
                            .append("don't force them into unrelated answers):\n")
                            .append(factList);
                    privateFacts = memories.size();
                    memories.forEach(m -> factIds.add(m.id()));
                }
            } catch (Exception e) {
                log.warn(
                        "Long-term memory recall failed for agent {} subject '{}' - continuing"
                                + " without it",
                        agentId,
                        subjectKey,
                        e);
            }
        }

        // Shared cross-agent memory tier (#847) - only recalled when this agent opted in via
        // shareMemoryWithTenant. Appended as a clearly-labeled second block rather than merged
        // into the private-memory list above, so the model (and anyone reading logs/traces) can
        // tell tenant-wide facts (contributed by any opted-in agent) apart from this agent's own
        // private recall - see TenantMemoryService/RememberSharedFactToolCallback's javadoc.
        TenantMemoryService tenantService = tenantMemoryService.getIfAvailable();
        if (tenantService != null) {
            try {
                if (agentService.isShareMemoryWithTenant(agentId)) {
                    List<TenantMemoryEntry> sharedMemories =
                            tenantService.recall(
                                    subjectKey,
                                    userMessage,
                                    embedding != null
                                            ? embedding
                                            : tenantService.queryEmbedding(userMessage));
                    if (!sharedMemories.isEmpty()) {
                        String sharedFactList =
                                sharedMemories.stream()
                                        .map(m -> "- " + m.content())
                                        .reduce((a, b) -> a + "\n" + b)
                                        .orElse("");
                        if (!block.isEmpty()) {
                            block.append("\n\n");
                        }
                        block.append(
                                        "Known facts about this user shared by other agents on this"
                                                + " team ")
                                .append(
                                        "(use if relevant, don't force them into unrelated"
                                                + " answers):\n")
                                .append(sharedFactList);
                        sharedFacts = sharedMemories.size();
                        sharedMemories.forEach(m -> factIds.add(m.id()));
                    }
                }
            } catch (Exception e) {
                log.warn(
                        "Shared tenant memory recall failed for agent {} subject '{}' - continuing"
                                + " without it",
                        agentId,
                        subjectKey,
                        e);
            }
        }

        if (block.isEmpty()) {
            return null;
        }
        if (report) {
            try {
                stepEvents.memoryRecall(
                        hasUserBlocks ? usedBlockLabels.size() : 0,
                        hasUserBlocks ? usedBlockLabels : List.of(),
                        privateFacts,
                        sharedFacts,
                        factIds);
            } catch (Exception e) {
                log.debug("Could not emit the memory step event - continuing", e);
            }
        }
        // Only trim when user blocks were prepended (their separator can trail). With none, the
        // text is byte-for-byte what it was before blocks existed.
        return hasUserBlocks ? block.toString().strip() : block.toString();
    }

    private boolean memoryEventsEnabled() {
        return memoryEventsEnabled != null && memoryEventsEnabled.getAsBoolean();
    }
}
