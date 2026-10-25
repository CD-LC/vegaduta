package com.agenticai.core.chat;

import com.agenticai.core.security.TenantScope;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;

/**
 * The real conversation of one run's tool loop, handed back to the application after every tool round.
 *
 * <p>The assistant -> tool_use -> tool_result -> assistant loop runs inside Spring AI's {@code
 * ChatModel}; the caller of a {@code ChatClient} gets only the final text. A caller that has to carry
 * the work on (a continuation pass after a reply that stopped early) used to rebuild the conversation
 * from that text and a summary of the tool log, which loses the tool-use and tool-result messages, their
 * ids, and provider metadata such as Gemini thought signatures that a replay needs. A run that wants the
 * real thing places one of these in the request's tool context under {@link #TOOL_CONTEXT_KEY} ({@code
 * ChatClient.ChatClientRequestSpec#toolContext}); {@link ArgumentSanitizingToolCallingManager} records
 * the conversation into it after each round, exactly as the model will be sent it.
 *
 * <p>Same shape and reason as {@link ToolLoopHistoryTransform}: the manager is a process-wide singleton
 * running on threads that know nothing about the turn, so what belongs to one run travels with that run's
 * request. A request without the key is unaffected. Thread-safe; one instance per run.
 */
public final class ToolLoopTranscript {

    /** Key in {@code ToolCallingChatOptions#getToolContext()} under which a run's transcript travels. */
    public static final String TOOL_CONTEXT_KEY = "vegaduta.toolLoopTranscript";

    private final AtomicReference<List<Message>> latest = new AtomicReference<>(List.of());

    /** This run's own round cap; null = the manager's. */
    private volatile Integer maxRounds;

    /**
     * This run's own cap on CUMULATIVE prompt tokens sent to the model across the whole tool loop
     * ({@code chat.toolLoop.maxPromptTokensPerTurn} of its tenant and agent); null = uncapped. The
     * per-call budget is in tool CALLS ({@code app.guardrails.max-tool-calls-per-turn}); this one is
     * in prompt tokens - the runaway shape a call cap misses is the loop whose every round resends a
     * near-full context window.
     */
    private volatile Long maxPromptTokens;
    private final AtomicLong promptTokensUsed = new AtomicLong();

    /** Gives this run a cumulative prompt-token budget; null or not positive leaves it uncapped. */
    public ToolLoopTranscript promptTokenBudget(Long maxTokens) {
        this.maxPromptTokens = maxTokens != null && maxTokens > 0 ? maxTokens : null;
        return this;
    }

    /** This run's prompt-token cap, or null. */
    Long maxPromptTokens() {
        return maxPromptTokens;
    }

    /** Adds one round's prompt size; returns the running total. */
    long addPromptTokens(long tokens) {
        return promptTokensUsed.addAndGet(Math.max(0, tokens));
    }

    /**
     * Gives this run its own cap on tool rounds per model invocation, in place of the manager's
     * process-wide one: an agent allowed more tool steps than the platform's highest ceiling needs a
     * backstop that follows its ceiling (see {@code AgentRuntimeSettingsResolver#toolLoopRounds}). Null or
     * not positive leaves the manager's cap.
     */
    public ToolLoopTranscript limitRounds(Integer rounds) {
        this.maxRounds = rounds != null && rounds > 0 ? rounds : null;
        return this;
    }

    /** This run's own tool-result clearing switch and threshold; null = the manager's. */
    private volatile Boolean clearingEnabled;
    private volatile Integer clearingThresholdChars;

    /**
     * Gives this run its own tool-result clearing ({@code chat.toolLoop.resultClearing.*} of the tenant and agent it is
     * for), in place of the manager's process-wide one. A null argument leaves the manager's value for that part.
     */
    public ToolLoopTranscript resultClearing(Boolean enabled, Integer thresholdChars) {
        this.clearingEnabled = enabled;
        this.clearingThresholdChars = thresholdChars != null && thresholdChars > 0 ? thresholdChars : null;
        return this;
    }

    /** This run's clearing switch, or null when it asked for none. */
    Boolean clearingEnabled() {
        return clearingEnabled;
    }

    /** This run's clearing threshold in characters, or null when it asked for none. */
    Integer clearingThresholdChars() {
        return clearingThresholdChars;
    }

    /** The agent whose prompt overrides apply to the text this run's tool loop writes; null = none. */
    private volatile UUID promptAgentId;

    /** The tenant to re-apply when that text is looked up on a loop thread; null = the calling thread's. */
    private volatile TenantScope promptTenant;

    /**
     * Tells the tool loop which agent and tenant this run is for, so the text the loop itself writes (the "tool
     * budget used up" result, the closing reply, the answer to a tool name that was never offered) is looked up in the
     * prompt registry for that agent and tenant and not only for whatever the loop's thread happens to carry.
     * {@code tenant} is {@link TenantScope#capture() captured} on the turn's thread.
     */
    public ToolLoopTranscript forPrompts(UUID agentId, TenantScope tenant) {
        this.promptAgentId = agentId;
        this.promptTenant = tenant;
        return this;
    }

    /** The agent this run's loop text is looked up for, or null. */
    public UUID promptAgentId() {
        return promptAgentId;
    }

    /** The tenant this run's loop text is looked up under, or null (the loop thread's own). */
    public TenantScope promptTenant() {
        return promptTenant;
    }

    /** The agent and tenant this run's loop text is for ({@link ChatPrompts.Run#NONE} when none was given). */
    ChatPrompts.Run promptRun() {
        UUID agent = promptAgentId;
        TenantScope tenant = promptTenant;
        return agent == null && tenant == null ? ChatPrompts.Run.NONE : new ChatPrompts.Run(agent, tenant);
    }

    /** This run's own round cap, or null. */
    Integer maxRounds() {
        return maxRounds;
    }

    /**
     * Tool rounds earlier passes of the same run already spent; 0 = this invocation counts alone.
     * A continuation pass is a fresh model invocation whose prompt hides the earlier passes behind its
     * nudge (the loop counts rounds only after the last user message), so the run's round cap would
     * refill on every pass without this - the caller carries the count across passes of one turn.
     */
    private volatile int roundsSpentBefore;

    /**
     * Tells this run how many tool rounds earlier passes of the same turn already ran; they count
     * against the run's round cap together with this invocation's own.
     */
    public ToolLoopTranscript roundsSpentBefore(int rounds) {
        this.roundsSpentBefore = Math.max(0, rounds);
        return this;
    }

    /** Rounds earlier passes of the same run already spent (0 for a lone invocation). */
    int roundsSpentBefore() {
        return roundsSpentBefore;
    }

    /**
     * The tool rounds this run's recorded conversation shows: the assistant tool-call messages after
     * its last user message - the same span {@link ArgumentSanitizingToolCallingManager} counts a round
     * on. 0 before the first round is recorded.
     */
    public int rounds() {
        List<Message> messages = latest.get();
        int first = 0;
        for (int i = messages.size() - 1; i >= 0; i--) {
            if (messages.get(i) instanceof UserMessage) {
                first = i + 1;
                break;
            }
        }
        int rounds = 0;
        for (int i = first; i < messages.size(); i++) {
            if (messages.get(i) instanceof AssistantMessage assistant && assistant.hasToolCalls()) {
                rounds++;
            }
        }
        return rounds;
    }

    /** Replaces the transcript with {@code conversation}, the history the next model round is sent. */
    public void record(List<Message> conversation) {
        if (conversation != null) {
            latest.set(List.copyOf(conversation));
        }
    }

    /**
     * The conversation after the latest tool round: the system and history messages, the user's message,
     * and every assistant tool-use / tool-result message of the run so far. Empty before the first round.
     * It ends with the last tool results; the model's reply to them is not in it.
     */
    public List<Message> latest() {
        return latest.get();
    }

    /** True once at least one tool round has been recorded. */
    public boolean hasRounds() {
        return !latest.get().isEmpty();
    }
}
