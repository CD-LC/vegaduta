package com.agenticai.core.agents.turn;

import com.agenticai.core.agents.core.AgentService;
import com.agenticai.core.agents.memory.AgentMemoryEntry;
import com.agenticai.core.agents.memory.AgentMemoryService;
import com.agenticai.core.agents.memory.MemoryBlocks;
import com.agenticai.core.agents.memory.QueryEmbedding;
import com.agenticai.core.agents.memory.SessionMemoryService;
import com.agenticai.core.agents.memory.TenantMemoryEntry;
import com.agenticai.core.agents.memory.TenantMemoryService;
import com.agenticai.core.agents.memory.spi.MemoryItemView;
import com.agenticai.core.agents.memory.spi.MemoryRecallRequest;
import com.agenticai.core.agents.memory.spi.MemoryRecallResult;
import com.agenticai.core.agents.memory.spi.UnifiedMemoryRecall;
import com.agenticai.core.agents.memorystore.MemoryRecallProperties;
import com.agenticai.core.agents.tools.AgentStepEventEmitter;
import com.agenticai.core.security.logging.LogSafe;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
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

    /** The settings-catalog key of the recall switch (docs/settings.md). */
    static final String RECALL_ENABLED_KEY = "memory.recall.enabled";

    private final ObjectProvider<AgentMemoryService> agentMemoryService;
    private final ObjectProvider<TenantMemoryService> tenantMemoryService;
    private final AgentService agentService;

    /**
     * {@code app.chat.sse.memory-events.enabled}, owned by {@link AgentChatService}; a supplier
     * because it is field-injected.
     */
    private final BooleanSupplier memoryEventsEnabled;

    /**
     * The prompt registry's view for an agent (its, its tenant's and the platform's overrides) for
     * the lines that frame the recalled facts; the shipped text until {@link ChatServiceParts}
     * wires the settings bean.
     */
    private volatile java.util.function.Function<UUID, ResolvedDirectives> prompts =
            ResolvedDirectives.DEFAULTS_FOR_AGENT;

    void usePrompts(java.util.function.Function<UUID, ResolvedDirectives> prompts) {
        this.prompts = prompts == null ? ResolvedDirectives.DEFAULTS_FOR_AGENT : prompts;
    }

    /**
     * {@code memory.recall.enabled} for an agent: the per-agent (and workspace, and platform {@code
     * app.memory.limits.recall-enabled}) switch for putting memory into the prompt. Everything on
     * until {@link ChatServiceParts} wires the settings catalog, which is also the answer of a
     * hand-built instance.
     */
    private volatile java.util.function.Predicate<UUID> recallEnabled = agentId -> true;

    void useRecallSwitch(java.util.function.Predicate<UUID> recallEnabled) {
        this.recallEnabled = recallEnabled == null ? agentId -> true : recallEnabled;
    }

    /**
     * {@code memory.write.enabled} for an agent, asked as "off": true = this agent's turns write no
     * memory (the conversation's rolling summary is not folded). Everything on until {@link
     * ChatServiceParts} wires the catalog.
     */
    private volatile java.util.function.Predicate<UUID> writesSwitchedOff = agentId -> false;

    void useWriteSwitch(java.util.function.Predicate<UUID> writesSwitchedOff) {
        this.writesSwitchedOff = writesSwitchedOff == null ? agentId -> false : writesSwitchedOff;
    }

    /** Whether the agent's memory writes are switched off; a failing lookup leaves them on. */
    boolean writesSwitchedOff(UUID agentId) {
        try {
            return writesSwitchedOff.test(agentId);
        } catch (RuntimeException e) {
            log.debug("Could not read the memory write switch - writes stay on", e);
            return false;
        }
    }

    /**
     * Whether a recalled long-term fact is relevant enough, and how many a turn may inject (see
     * {@link MemoryRecallProperties}).
     */
    private MemoryRecallProperties limitsOf(AgentMemoryService service) {
        MemoryRecallProperties limits = service == null ? null : service.recallLimits();
        return limits == null ? MemoryRecallProperties.off() : limits;
    }

    /**
     * The unified recall (ADR-0028), absent until wired and when the memory-store module is not on
     * the classpath.
     */
    private volatile ObjectProvider<UnifiedMemoryRecall> unifiedRecall;

    void useUnifiedRecall(ObjectProvider<UnifiedMemoryRecall> unifiedRecall) {
        this.unifiedRecall = unifiedRecall;
    }

    /**
     * What the unified recall added to one turn: the rendered block, the items in it, a degraded
     * note.
     */
    private record Unified(String text, List<TurnMemoryRecall.UnifiedItem> items, String note) {
        static final Unified NONE = new Unified(null, List.of(), null);
    }

    /**
     * Unified recall for the turn: nothing unless a {@link UnifiedMemoryRecall} bean exists and its
     * gate is on for the agent (it answers "disabled" otherwise). Called in-process on the turn's
     * own thread, so the tenant is set. Any failure yields no block and a {@code degraded:error}
     * note, never a failed turn.
     *
     * @param usedTokens tokens the always-in-context memory blocks already took of the shared
     *     budget (charged first)
     * @param legacyIds ids of facts the legacy path already injected, so none appears twice
     * @param maxNew most items the unified block may add on top of the legacy ones (0 = the
     *     composer's own cap)
     */
    private Unified recallUnified(
            UUID agentId,
            String subjectKey,
            String userMessage,
            long usedTokens,
            Set<UUID> legacyIds,
            int maxNew) {
        ObjectProvider<UnifiedMemoryRecall> provider = unifiedRecall;
        if (provider == null) {
            return Unified.NONE;
        }
        try {
            UnifiedMemoryRecall recall = provider.getIfAvailable();
            if (recall == null) {
                return Unified.NONE;
            }
            long remaining = recall.totalTokens() - usedTokens;
            if (remaining <= 0) {
                return Unified.NONE;
            }
            MemoryRecallResult result =
                    recall.compose(
                            new MemoryRecallRequest(
                                    agentId,
                                    subjectKey,
                                    userMessage,
                                    Set.of(),
                                    maxNew > 0 ? maxNew + legacyIds.size() : 0,
                                    (int) remaining));
            if (result == null || UnifiedMemoryRecall.DISABLED.equals(result.degradedReason())) {
                return Unified.NONE;
            }
            String note =
                    result.degradedReason() == null ? null : "degraded:" + result.degradedReason();
            List<MemoryItemView> candidates = result.items();
            if (maxNew > 0) {
                candidates =
                        candidates.stream()
                                .filter(i -> !legacyIds.contains(i.id()))
                                .limit(maxNew)
                                .toList();
            }
            UnifiedMemoryBlock.Rendered rendered =
                    UnifiedMemoryBlock.render(candidates, (int) remaining, legacyIds);
            return rendered == null
                    ? new Unified(null, List.of(), note)
                    : new Unified(rendered.text(), rendered.items(), note);
        } catch (Exception e) {
            log.warn(
                    "Unified memory recall failed for agent {} - continuing without it",
                    LogSafe.clean(agentId),
                    e);
            return new Unified(null, List.of(), "degraded:error");
        }
    }

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
        return recallMemoryFacts(
                        agentId, subjectKey, userMessage, stepEvents, prompts.apply(agentId))
                .block();
    }

    /**
     * The recall outcome: the assembled block ({@code null} when nothing applied, same contract as
     * {@link #recallMemoryFactsBlock}) and how many stored facts were recalled into it. The count
     * is what lets the turn tell auto-injected retrieval - facts a {@code remember_fact} call or a
     * shared tier wrote, so attacker-storable content - apart from the owner's own
     * always-in-context blocks (persona, profile, notes), which ride in the same string but are not
     * taint-worthy on their own.
     */
    record MemoryRecall(String block, int recalledFacts, TurnMemoryRecall.Trace trace) {

        MemoryRecall(String block, int recalledFacts) {
            this(block, recalledFacts, null);
        }

        /** Whether any private or shared fact was recalled into {@link #block()}. */
        boolean hasRecalledFacts() {
            return recalledFacts > 0;
        }
    }

    /**
     * As {@link #recallMemoryFacts(UUID, String, String, AgentStepEventEmitter,
     * ResolvedDirectives)}, with this turn's prompt view.
     */
    MemoryRecall recallMemoryFacts(
            UUID agentId, String subjectKey, String userMessage, AgentStepEventEmitter stepEvents) {
        return recallMemoryFacts(
                agentId, subjectKey, userMessage, stepEvents, prompts.apply(agentId));
    }

    /**
     * As {@link #recallMemoryFactsBlock(UUID, String, String, AgentStepEventEmitter,
     * ResolvedDirectives)}, returning the block with its fact count for taint marking (see {@link
     * MemoryRecall}).
     */
    MemoryRecall recallMemoryFacts(
            UUID agentId,
            String subjectKey,
            String userMessage,
            AgentStepEventEmitter stepEvents,
            ResolvedDirectives pd) {
        if (!recallSwitchedOn(agentId)) {
            // Memory recall is off for this agent: nothing is read and nothing is injected. The
            // trace says so, so a turn
            // that did not look is not mistaken for one that found nothing.
            return new MemoryRecall(null, 0, TurnMemoryRecall.Trace.disabled());
        }
        StringBuilder block = new StringBuilder();
        boolean hasUserBlocks = false;
        long blockTokens = 0;
        boolean report = stepEvents != null && stepEvents.isEnabled() && memoryEventsEnabled();
        List<String> usedBlockLabels = List.of();
        int privateFacts = 0;
        int sharedFacts = 0;
        List<UUID> factIds = new ArrayList<>();
        List<UUID> privateIds = new ArrayList<>();
        List<UUID> sharedIds = new ArrayList<>();

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
                    blocks = MemoryBlocks.withNotesHeader(blocks, pd.text(MemoryBlocks.NOTES_KEY));
                    block.append(blocks).append("\n\n");
                    hasUserBlocks = true;
                    blockTokens = SessionMemoryService.estimateTokens(blocks);
                }
            } catch (Exception e) {
                log.warn(
                        "Memory block render failed for subject '{}' - continuing without it",
                        LogSafe.clean(subjectKey),
                        e);
            }
        }

        // One embedding of the message for both stores below: the sidecar is asked once, by
        // whichever
        // recall needs it first.
        QueryEmbedding embedding = null;
        AgentMemoryService memoryService = agentMemoryService.getIfAvailable();
        MemoryRecallProperties limits = limitsOf(memoryService);
        List<AgentMemoryEntry> memories = List.of();
        if (memoryService != null) {
            try {
                embedding = memoryService.queryEmbedding(userMessage);
                memories =
                        memoryService.recall(agentId, subjectKey, userMessage, embedding).stream()
                                .filter(m -> limits.passes(1.0 - m.distance()))
                                .toList();
            } catch (Exception e) {
                log.warn(
                        "Long-term memory recall failed for agent {} subject '{}' - continuing"
                                + " without it",
                        LogSafe.clean(agentId),
                        LogSafe.clean(subjectKey),
                        e);
            }
        }

        // Shared cross-agent memory tier (#847) - only recalled when this agent opted in via
        // shareMemoryWithTenant. Appended as a clearly-labeled second block rather than merged
        // into the private-memory list above, so the model (and anyone reading logs/traces) can
        // tell tenant-wide facts (contributed by any opted-in agent) apart from this agent's own
        // private recall - see TenantMemoryService/RememberSharedFactToolCallback's javadoc.
        TenantMemoryService tenantService = tenantMemoryService.getIfAvailable();
        List<TenantMemoryEntry> sharedMemories = List.of();
        if (tenantService != null) {
            try {
                if (agentService.isShareMemoryWithTenant(agentId)) {
                    sharedMemories =
                            tenantService
                                    .recall(
                                            subjectKey,
                                            userMessage,
                                            embedding != null
                                                    ? embedding
                                                    : tenantService.queryEmbedding(userMessage),
                                            agentId)
                                    .stream()
                                    .filter(m -> limits.passes(1.0 - m.distance()))
                                    .toList();
                }
            } catch (Exception e) {
                log.warn(
                        "Shared tenant memory recall failed for agent {} subject '{}' - continuing"
                                + " without it",
                        LogSafe.clean(agentId),
                        LogSafe.clean(subjectKey),
                        e);
            }
        }

        // app.memory.recall.max-items: one turn injects at most this many recalled facts across
        // both stores, the closest
        // first (a tie keeps the private fact). 0 = each store's own top-k, as before.
        int cap = limits.getMaxItems();
        if (cap > 0 && memories.size() + sharedMemories.size() > cap) {
            double cutoff = nthBestSimilarity(memories, sharedMemories, cap);
            memories = keepClosest(memories, cutoff, cap);
            sharedMemories = keepClosest(sharedMemories, cutoff, cap - memories.size());
        }
        if (!memories.isEmpty()) {
            String factList =
                    memories.stream()
                            .map(m -> "- " + m.content())
                            .reduce((a, b) -> a + "\n" + b)
                            .orElse("");
            block.append(
                    pd.textOrShipped("memory.recall.private", java.util.Map.of("facts", factList)));
            privateFacts = memories.size();
            memories.forEach(
                    m -> {
                        factIds.add(m.id());
                        privateIds.add(m.id());
                    });
        }
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
                    pd.textOrShipped(
                            "memory.recall.shared", java.util.Map.of("facts", sharedFactList)));
            sharedFacts = sharedMemories.size();
            sharedMemories.forEach(
                    m -> {
                        factIds.add(m.id());
                        sharedIds.add(m.id());
                    });
        }

        // Unified memory (default OFF: no bean call result, no text, no trace fields). Its items
        // are TRUSTED-only and,
        // like the legacy facts, auto-injected, so they count as recalled facts for taint marking.
        int roomForUnified = cap > 0 ? Math.max(0, cap - factIds.size()) : 0;
        Unified unified =
                cap > 0 && roomForUnified == 0
                        ? Unified.NONE
                        : recallUnified(
                                agentId,
                                subjectKey,
                                userMessage,
                                blockTokens,
                                new HashSet<>(factIds),
                                roomForUnified);
        if (unified.text() != null) {
            if (!block.isEmpty()) {
                block.append("\n\n");
            }
            block.append(unified.text());
        }
        if (block.isEmpty()) {
            return new MemoryRecall(
                    null,
                    0,
                    new TurnMemoryRecall.Trace(true, List.of(), List.of(), List.of(), 0)
                            .withUnified(unified.items(), unified.note()));
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
        String text = hasUserBlocks ? block.toString().strip() : block.toString();
        // Block labels are known only on a step-event turn (the labelled render is what reports
        // them); otherwise the
        // trace names the blocks as one unlabelled group, so their presence is still visible.
        List<String> traceBlocks =
                !hasUserBlocks
                        ? List.of()
                        : usedBlockLabels.isEmpty() ? List.of("memory_blocks") : usedBlockLabels;
        return new MemoryRecall(
                text,
                privateFacts + sharedFacts + unified.items().size(),
                new TurnMemoryRecall.Trace(
                                true,
                                privateIds,
                                sharedIds,
                                traceBlocks,
                                SessionMemoryService.estimateTokens(text))
                        .withUnified(unified.items(), unified.note()));
    }

    /**
     * Similarity of the {@code n}-th closest fact over both lists (the cutoff that keeps {@code n}
     * of them).
     */
    private static double nthBestSimilarity(
            List<AgentMemoryEntry> privateFacts, List<TenantMemoryEntry> sharedFacts, int n) {
        List<Double> sims = new ArrayList<>();
        privateFacts.forEach(m -> sims.add(1.0 - m.distance()));
        sharedFacts.forEach(m -> sims.add(1.0 - m.distance()));
        sims.sort(java.util.Comparator.reverseOrder());
        return sims.get(Math.min(n, sims.size()) - 1);
    }

    /** The facts at or above {@code cutoff}, in their order, at most {@code max}. */
    private static <T> List<T> keepClosest(List<T> facts, double cutoff, int max) {
        List<T> out = new ArrayList<>();
        for (T fact : facts) {
            double sim =
                    fact instanceof AgentMemoryEntry a
                            ? 1.0 - a.distance()
                            : 1.0 - ((TenantMemoryEntry) fact).distance();
            if (out.size() < max && sim >= cutoff) {
                out.add(fact);
            }
        }
        return out;
    }

    /** The switch's answer; a failing lookup leaves recall on, as it is without the switch. */
    private boolean recallSwitchedOn(UUID agentId) {
        try {
            return recallEnabled.test(agentId);
        } catch (RuntimeException e) {
            log.debug("Could not read the memory recall switch - recall stays on", e);
            return true;
        }
    }

    private boolean memoryEventsEnabled() {
        return memoryEventsEnabled != null && memoryEventsEnabled.getAsBoolean();
    }
}
