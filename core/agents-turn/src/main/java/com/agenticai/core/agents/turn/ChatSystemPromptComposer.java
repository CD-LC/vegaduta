package com.agenticai.core.agents.turn;

import static com.agenticai.core.agents.turn.ChatPromptDirectives.joinMemoryBlocks;
import static com.agenticai.core.agents.turn.ChatPromptDirectives.withSkillsBlock;

import com.agenticai.core.agents.context.ChatContextCompaction;
import com.agenticai.core.agents.skills.SkillPromptBlock;
import com.agenticai.core.prompts.ResolvedPrompt;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.ai.tool.ToolCallback;

/**
 * Builds the standing system text of one turn from its already-read inputs: the static system
 * prompt (agent prompt, procedures, skills) and the per-turn block that follows it (date, standing
 * directives, hook note, tool-record note, custom instructions, recalled memory). It is the ONE
 * place that decides which text goes in which order, shared by {@link ChatPromptAssembler#assemble}
 * (a real turn) and the prompt preview, so the preview shows exactly what a turn would send. Pure:
 * no I/O, no side effects; the caller reads the tool list, the skills/procedures blocks and the
 * recalled memory and passes them in.
 *
 * <p>Every block that comes from the prompt registry carries its {@link ResolvedPrompt} (key, scope,
 * version, source); the others (the agent's own prompt, recalled facts) carry an id only.
 */
final class ChatSystemPromptComposer {

    private ChatSystemPromptComposer() {}

    /** Which standing directives are switched on for this deployment (the {@code app.chat.directives.*} flags). */
    record Switches(
            boolean memoryUsage,
            boolean toolSearch,
            boolean contextCompaction,
            boolean browserV2,
            boolean artifacts,
            boolean artifactsAllAgents,
            String artifactsAgentIds) {

        /**
         * The service's switches as they stand now (several are field-injected after construction,
         * so they are read at call time). A turn and the preview both build theirs here.
         */
        static Switches of(AgentChatService svc) {
            ChatContextCompaction compaction = svc.contextCompaction;
            return new Switches(
                    svc.memoryUsageDirectiveEnabled,
                    svc.toolSearchDirectiveEnabled,
                    svc.contextCompactionDirectiveEnabled && compaction != null && compaction.isEnabled(),
                    svc.browserV2DirectiveEnabled,
                    svc.artifactsDirectiveEnabled,
                    svc.artifactsDirectiveAllAgents,
                    svc.artifactsDirectiveAgentIds);
        }
    }

    record Input(
            String basePrompt,
            boolean rlPromptVariant,
            String proceduresBlock,
            String skillsBlock,
            LocalDate todayUtc,
            ResolvedDirectives directives,
            PromptDirectiveComposer composer,
            List<ToolCallback> tools,
            boolean anyMcpToolsOffered,
            boolean smallModelTurn,
            Switches switches,
            UUID agentId,
            String hookContextNote,
            String toolRecordTrustNote,
            String recalledMemoryBlock) {}

    /** One piece of text with where it came from; {@code prompt} is null for text that is not a registry key. */
    record Block(String id, ResolvedPrompt prompt, String text) {}

    /**
     * {@code systemPrompt} is the static system text, {@code memoryBlock} the per-turn system block
     * (null when empty); {@code systemBlocks} and {@code memoryBlocks} are their parts in order.
     */
    record Composed(
            String systemPrompt,
            String memoryBlock,
            List<Block> systemBlocks,
            List<Block> memoryBlocks) {}

    static Composed compose(Input in) {
        ResolvedDirectives pd = in.directives();
        // The skills block is built where no agent or tenant is known; its opening wording is the
        // registry's (skills.header / skills.header.compact), so the owner's override is applied here.
        boolean compactSkills = in.smallModelTurn();
        ResolvedPrompt skillsHeader =
                in.skillsBlock() == null || in.skillsBlock().isBlank()
                        ? null
                        : pd.resolved(
                                compactSkills ? SkillPromptBlock.COMPACT_HEADER_KEY : SkillPromptBlock.HEADER_KEY);
        String skillsBlock =
                SkillPromptBlock.withHeader(
                        in.skillsBlock(),
                        skillsHeader == null ? null : skillsHeader.textOrNull(),
                        compactSkills);
        // Procedures go FIRST: binding business rules outrank the skills advertisement.
        String systemPrompt =
                withSkillsBlock(withSkillsBlock(in.basePrompt(), in.proceduresBlock()), skillsBlock);
        List<Block> system = new ArrayList<>();
        system.add(
                new Block(
                        in.rlPromptVariant() ? "agent.system_prompt.rl_variant" : "agent.system_prompt",
                        null,
                        in.basePrompt()));
        addNonBlank(system, "procedures", in.proceduresBlock());
        if (skillsBlock != null && !skillsBlock.isBlank()) {
            system.add(new Block("skills", skillsHeader, skillsBlock));
        }

        Switches sw = in.switches();
        List<Block> memory = new ArrayList<>();
        addResolved(memory, pd.resolved(PromptDirectiveRegistry.Keys.CURRENT_DATE));
        addResolved(memory, pd.resolved(sw.memoryUsage() ? DirectiveKeys.memoryUsageKey(in.tools()) : null));
        for (ResolvedPrompt r :
                in.composer()
                        .blocks(
                                new PromptDirectiveComposer.Context(
                                        in.tools(), in.anyMcpToolsOffered(), in.smallModelTurn(), pd))) {
            addResolved(memory, r);
        }
        addResolved(memory, pd.resolved(sw.toolSearch() ? DirectiveKeys.toolSearchKey(in.tools()) : null));
        addNonNull(memory, "hook.context_note", in.hookContextNote());
        addResolved(memory, pd.resolved(DirectiveKeys.contextCompactionKey(sw.contextCompaction(), in.tools())));
        addResolved(memory, pd.resolved(sw.browserV2() ? DirectiveKeys.browserV2Key(in.tools()) : null));
        addResolved(
                memory,
                pd.resolved(
                        DirectiveKeys.artifactsKey(
                                sw.artifacts(), sw.artifactsAllAgents(), sw.artifactsAgentIds(), in.agentId())));
        // When a replayed assistant turn carries the platform's tool record, the model is told it is
        // reliable (and counts as tool use in this conversation) - see ToolLedgerReplay.
        addNonNull(memory, "tool_ledger.trust_note", in.toolRecordTrustNote());
        ResolvedPrompt custom = pd.resolved(PromptDirectiveRegistry.Keys.CUSTOM_INSTRUCTIONS);
        if (custom != null && custom.textOrNull() != null && !custom.text().isBlank()) {
            memory.add(new Block(custom.key(), custom, custom.text().strip()));
        }
        addNonNull(memory, "memory.recall", in.recalledMemoryBlock());

        String memoryBlock = joinMemoryBlocks(memory.stream().map(Block::text).toArray(String[]::new));
        return new Composed(systemPrompt, memoryBlock, List.copyOf(system), List.copyOf(memory));
    }

    private static void addResolved(List<Block> out, ResolvedPrompt r) {
        if (r != null && r.textOrNull() != null) {
            out.add(new Block(r.key(), r, r.text()));
        }
    }

    /** A block {@code joinMemoryBlocks} would keep: any non-null text (a blank one too, as before). */
    private static void addNonNull(List<Block> out, String id, String text) {
        if (text != null) {
            out.add(new Block(id, null, text));
        }
    }

    /** A block {@code withSkillsBlock} would keep: non-blank text. */
    private static void addNonBlank(List<Block> out, String id, String text) {
        if (text != null && !text.isBlank()) {
            out.add(new Block(id, null, text));
        }
    }
}
