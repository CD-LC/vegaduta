package com.agenticai.core.agents.turn;

import com.agenticai.core.agents.core.Agent;
import com.agenticai.core.agents.core.ReasoningDepth;
import com.agenticai.core.agents.memory.ChatTurn;
import com.agenticai.core.agents.memory.SessionMemoryService;
import com.agenticai.core.agents.tools.AgentStepEventEmitter;
import com.agenticai.core.agents.tools.AgentToolSelection;
import com.agenticai.core.agents.tools.ResolvedToolList;
import com.agenticai.core.prompts.PromptBudget;
import com.agenticai.core.prompts.PromptProperties;
import com.agenticai.core.prompts.PromptSource;
import com.agenticai.core.prompts.ResolvedPrompt;
import com.agenticai.core.security.TenantContext;
import com.agenticai.core.sharing.PermissionLevel;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Service;

/**
 * The real prompt of an agent, without running a turn: the system text and the tool list exactly as
 * {@link ChatPromptAssembler#assemble} would hand them to the model, each block tagged with the
 * registry key, scope, version and source that produced it and a token estimate. Nothing is sent to
 * a model and nothing is stored (no session turn, no cache entry, no RL episode, no budget spent).
 *
 * <p>It shares the assembly with real turns through {@link ChatSystemPromptComposer#compose}: the
 * same code decides which directive applies to which tool list and in which order, so the two
 * cannot drift apart. What differs is only where the inputs come from; the {@code notes} of a
 * result list every input the preview cannot know (hook notes, the live model's small-model
 * status, RL arms).
 */
@Service
public class PromptPreviewService {

    private static final Logger log = LoggerFactory.getLogger(PromptPreviewService.class);

    /** Preview input; every field is optional. */
    public record Request(
            String message, String sessionId, Boolean smallModel, Boolean includeMemoryRecall) {}

    /** One piece of the prompt with its provenance. {@code key} is null for text that is not a registry key. */
    public record BlockView(
            String message,
            String id,
            String key,
            String source,
            String scope,
            Integer version,
            String hash,
            int estimatedTokens,
            String text) {}

    public record ToolView(String name, String description, String inputSchema, int estimatedTokens) {}

    public record Preview(
            UUID agentId,
            boolean smallModel,
            String systemPrompt,
            String memoryBlock,
            List<BlockView> blocks,
            List<ToolView> tools,
            int promptEstimatedTokens,
            int toolsEstimatedTokens,
            int tokenBudget,
            boolean overBudget,
            String tokenEstimateNote,
            List<String> notes) {}

    private final AgentChatService svc;
    private final PromptProperties properties;

    public PromptPreviewService(AgentChatService svc, PromptProperties properties) {
        this.svc = svc;
        this.properties = properties;
    }

    /**
     * Builds the preview for {@code agentId}; the caller needs EDITOR access to the agent. {@code
     * subjectKey} is the person the memory and history belong to (the caller).
     */
    public Preview preview(UUID agentId, Request request) {
        Request req = request == null ? new Request(null, null, null, null) : request;
        Agent live = svc.agentService.requireAccess(agentId, PermissionLevel.EDITOR);
        Agent agent = effectiveAgent(agentId, live);
        String message = req.message() == null || req.message().isBlank() ? "preview" : req.message();
        String subjectKey = TenantContext.current().map(TenantContext::getSubject).orElse("preview");
        String sessionId = req.sessionId() == null || req.sessionId().isBlank() ? null : req.sessionId();
        boolean smallModel = Boolean.TRUE.equals(req.smallModel());
        List<String> notes = new ArrayList<>();

        // The tool list as a turn resolves it (same resolver, same trimming); a FAST depth only
        // because the RIGOROUS per-tool verifier needs a live model client, which a preview has none of.
        List<ToolCallback> tools =
                ResolvedToolList.copyOf(
                        svc.agentToolProvider.resolveToolsFor(
                                agentId, sessionId, subjectKey, message, null, ReasoningDepth.FAST));
        boolean anyMcp =
                tools instanceof ResolvedToolList resolved && resolved.anyMcpToolsOffered() != null
                        ? resolved.anyMcpToolsOffered()
                        : svc.agentToolProvider.anyMcpToolsOfferedFor(agentId, sessionId, message, null);
        if (smallModel) {
            tools = AgentToolSelection.capForSmallModel(tools, message, Set.of(), svc.smallModel);
        }

        boolean hasToolRecord = false;
        if (sessionId != null) {
            String memorySessionId =
                    SessionMemoryService.scopedSessionId(
                            TenantContext.current().map(TenantContext::getTenantId).orElse(null),
                            agentId,
                            subjectKey,
                            sessionId);
            List<ChatTurn> history = svc.sessionMemoryService.getHistory(memorySessionId);
            hasToolRecord = history.stream().anyMatch(t -> t.tools() != null);
        } else {
            notes.add("No sessionId: the conversation history is not replayed, so the tool-record note is absent.");
        }
        String recalled = null;
        if (Boolean.TRUE.equals(req.includeMemoryRecall())) {
            recalled = svc.memoryRecall.recallMemoryFactsBlock(agentId, subjectKey, message, AgentStepEventEmitter.disabled());
        } else {
            notes.add("Recalled memory is not included (set includeMemoryRecall=true to read it as a turn would).");
        }
        notes.add("Hook context notes, RL prompt-variant and tool-subset arms, and the live model's small-model status"
                + " are decided during a real turn and are not simulated; smallModel is taken from the request.");
        notes.add("Tools are resolved at FAST depth (the RIGOROUS per-tool verifier needs a live model client).");
        if (agent != live) {
            notes.add("The published version of the agent is shown (explicit-publish versioning is on).");
        }

        LocalDate todayUtc = LocalDate.now(ZoneOffset.UTC);
        ResolvedDirectives directives = svc.promptDirectiveSettings.forTurn(todayUtc, agentId).recording();
        ChatSystemPromptComposer.Composed composed =
                ChatSystemPromptComposer.compose(
                        new ChatSystemPromptComposer.Input(
                                agent.getSystemPrompt(),
                                false,
                                svc.agentToolProvider.proceduresPromptBlock(agentId),
                                svc.agentToolProvider.skillsPromptBlock(agentId, tools, smallModel),
                                todayUtc,
                                directives,
                                svc.directiveComposer,
                                tools,
                                anyMcp,
                                smallModel,
                                ChatSystemPromptComposer.Switches.of(svc),
                                agentId,
                                null,
                                hasToolRecord ? svc.turns.toolLedger.getTrustNote() : null,
                                recalled));
        return toPreview(agentId, smallModel, composed, tools, notes);
    }

    private Preview toPreview(
            UUID agentId,
            boolean smallModel,
            ChatSystemPromptComposer.Composed composed,
            List<ToolCallback> tools,
            List<String> notes) {
        PromptBudget budget = new PromptBudget(properties.getSystemPromptTokenBudget(), properties.getCharsPerToken());
        List<BlockView> blocks = new ArrayList<>();
        for (ChatSystemPromptComposer.Block b : composed.systemBlocks()) {
            blocks.add(view("system", b, budget));
        }
        for (ChatSystemPromptComposer.Block b : composed.memoryBlocks()) {
            blocks.add(view("memory", b, budget));
        }
        List<ToolView> toolViews = new ArrayList<>();
        int toolTokens = 0;
        PromptBudget toolCounter = new PromptBudget(0, properties.getCharsPerToken());
        for (ToolCallback t : tools) {
            var def = t.getToolDefinition();
            int tokens = toolCounter.estimate(def.name() + def.description() + def.inputSchema());
            toolTokens += tokens;
            toolViews.add(new ToolView(def.name(), def.description(), def.inputSchema(), tokens));
        }
        return new Preview(
                agentId,
                smallModel,
                composed.systemPrompt(),
                composed.memoryBlock(),
                List.copyOf(blocks),
                List.copyOf(toolViews),
                budget.used(),
                toolTokens,
                budget.limit(),
                budget.overBudget(),
                "Token counts are a rule of thumb (characters / " + properties.getCharsPerToken() + "), not a tokenizer.",
                List.copyOf(notes));
    }

    private static BlockView view(String message, ChatSystemPromptComposer.Block b, PromptBudget budget) {
        ResolvedPrompt p = b.prompt();
        int tokens = budget.add(b.id(), b.text());
        if (p == null) {
            String source = b.id().startsWith("agent.") ? "agent_config" : "code";
            return new BlockView(message, b.id(), null, source, null, null, null, tokens, b.text());
        }
        return new BlockView(
                message,
                b.id(),
                p.key(),
                p.source().name().toLowerCase(Locale.ROOT),
                p.scope() == null ? null : p.scope().dbValue(),
                p.source() == PromptSource.DEFAULT || p.source() == PromptSource.FALLBACK ? null : p.version(),
                p.hash(),
                tokens,
                b.text());
    }

    private Agent effectiveAgent(UUID agentId, Agent live) {
        try {
            var resolver = svc.versionResolver.getIfAvailable();
            return resolver == null ? live : resolver.effectiveAgent(live);
        } catch (RuntimeException e) {
            log.warn("Version resolution failed for agent {} - previewing the live row", agentId, e);
            return live;
        }
    }
}
