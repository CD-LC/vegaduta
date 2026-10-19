package com.agenticai.core.agents.memory;

import com.agenticai.core.agents.tools.ToolCallLog;
import com.agenticai.core.agents.tools.ToolCallLogRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

/**
 * Read-only "which conversation taught this?" lookup for one remembered fact.
 *
 * <p>The memory tables carry no session column and this adds none: the answer is derived from the
 * {@code tool_call_log} row of the {@code remember_fact} / {@code remember_shared_fact} call that
 * wrote the fact. A row matches only when its output names the fact's id (both tools return {@code
 * memoryId}). Matching on the fact text is deliberately NOT done: {@code tool_call_log} has no
 * subject column, so the same text saved by another user on the same agent would hand this caller
 * that user's session id. Only rows by the fact's own agent within {@link #WINDOW} of the fact's
 * creation time are considered.
 *
 * <p>Anything that cannot be traced - a fact typed in by hand, an old row whose log was pruned or
 * never written, a lookup failure - is {@code matched=false}, never an error.
 */
@Service
public class MemoryProvenanceService {

    private static final Logger log = LoggerFactory.getLogger(MemoryProvenanceService.class);

    static final String SCOPE_AGENT = "agent";
    static final String SCOPE_SHARED = "shared";
    static final String TOOL_REMEMBER_FACT = "remember_fact";
    static final String TOOL_REMEMBER_SHARED_FACT = "remember_shared_fact";

    /** How far a tool call's start may sit from the fact row's {@code created_at}. */
    static final Duration WINDOW = Duration.ofMinutes(2);

    private final ObjectProvider<AgentMemoryService> agentMemoryService;
    private final ObjectProvider<TenantMemoryService> tenantMemoryService;
    private final ToolCallLogRepository toolCallLogRepository;

    public MemoryProvenanceService(
            ObjectProvider<AgentMemoryService> agentMemoryService,
            ObjectProvider<TenantMemoryService> tenantMemoryService,
            ToolCallLogRepository toolCallLogRepository) {
        this.agentMemoryService = agentMemoryService;
        this.tenantMemoryService = tenantMemoryService;
        this.toolCallLogRepository = toolCallLogRepository;
    }

    /**
     * Where a fact came from. {@code agentId} is the agent that holds (private) or contributed
     * (shared) the fact, null for a hand-added shared fact. {@code sessionId} is set only when
     * {@code matched}. {@code learnedAt} is the tool call's start when matched, otherwise the
     * fact's creation time.
     */
    public record FactSource(UUID agentId, String sessionId, Instant learnedAt, boolean matched) {}

    /** The fact as far as provenance needs it. */
    record OwnedFact(UUID id, UUID agentId, String content, Instant createdAt) {}

    /**
     * @return empty when the fact does not exist or is not the caller's (or memory is unavailable)
     * @throws IllegalArgumentException for a scope other than "agent" / "shared"
     */
    public Optional<FactSource> sourceOf(String scope, UUID factId, String subjectKey) {
        if (!SCOPE_AGENT.equals(scope) && !SCOPE_SHARED.equals(scope)) {
            throw new IllegalArgumentException("Unknown memory scope.");
        }
        if (factId == null || subjectKey == null || subjectKey.isBlank()) {
            return Optional.empty();
        }
        Optional<OwnedFact> owned =
                SCOPE_AGENT.equals(scope)
                        ? ownedAgentFact(factId, subjectKey)
                        : ownedSharedFact(factId, subjectKey);
        if (owned.isEmpty()) {
            return Optional.empty();
        }
        OwnedFact fact = owned.get();
        FactSource unmatched = new FactSource(fact.agentId(), null, fact.createdAt(), false);
        if (fact.agentId() == null || fact.createdAt() == null) {
            return Optional.of(unmatched);
        }
        try {
            String toolName =
                    SCOPE_AGENT.equals(scope) ? TOOL_REMEMBER_FACT : TOOL_REMEMBER_SHARED_FACT;
            List<ToolCallLog> candidates =
                    toolCallLogRepository
                            .findByAgentIdAndToolNameInAndStartedAtBetweenOrderByStartedAtAsc(
                                    fact.agentId(),
                                    List.of(toolName),
                                    fact.createdAt().minus(WINDOW),
                                    fact.createdAt().plus(WINDOW));
            ToolCallLog hit = bestMatch(candidates, fact.id());
            if (hit == null) {
                return Optional.of(unmatched);
            }
            return Optional.of(
                    new FactSource(fact.agentId(), hit.getSessionId(), hit.getStartedAt(), true));
        } catch (Exception e) {
            log.warn(
                    "Memory provenance lookup failed for fact {} - reporting it as untraced",
                    factId,
                    e);
            return Optional.of(unmatched);
        }
    }

    private Optional<OwnedFact> ownedAgentFact(UUID factId, String subjectKey) {
        AgentMemoryService service = agentMemoryService.getIfAvailable();
        if (service == null) {
            return Optional.empty();
        }
        return service.listAllForSubject(subjectKey).stream()
                .filter(m -> factId.equals(m.id()))
                .findFirst()
                .map(m -> new OwnedFact(m.id(), m.agentId(), m.content(), m.createdAt()));
    }

    private Optional<OwnedFact> ownedSharedFact(UUID factId, String subjectKey) {
        TenantMemoryService service = tenantMemoryService.getIfAvailable();
        if (service == null) {
            return Optional.empty();
        }
        return service.listFactsForSubject(subjectKey).stream()
                .filter(m -> factId.equals(m.id()))
                .findFirst()
                .map(m -> new OwnedFact(m.id(), m.createdByAgentId(), m.content(), m.createdAt()));
    }

    /**
     * The tool call that wrote the fact - the first successful one whose output names the fact id -
     * or null. The id is a random UUID the caller already owns, so a hit cannot belong to someone
     * else.
     */
    static ToolCallLog bestMatch(List<ToolCallLog> candidates, UUID factId) {
        if (candidates == null || candidates.isEmpty() || factId == null) {
            return null;
        }
        String idText = factId.toString();
        for (ToolCallLog row : candidates) {
            if (row == null || row.getErrorMessage() != null) {
                continue;
            }
            if (row.getToolOutput() != null && row.getToolOutput().contains(idText)) {
                return row;
            }
        }
        return null;
    }
}
