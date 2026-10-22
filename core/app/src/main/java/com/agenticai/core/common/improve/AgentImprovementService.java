package com.agenticai.core.common.improve;

import com.agenticai.core.agents.assist.AssistService;
import com.agenticai.core.agents.core.Agent;
import com.agenticai.core.agents.core.AgentRepository;
import com.agenticai.core.agents.core.AgentService;
import com.agenticai.core.audit.IssueStatus;
import com.agenticai.core.audit.PlatformIssue;
import com.agenticai.core.audit.PlatformIssueRepository;
import com.agenticai.core.chat.structured.StructuredOutput;
import com.agenticai.core.guardrails.procedures.Procedure;
import com.agenticai.core.guardrails.procedures.ProcedureService;
import com.agenticai.core.guardrails.procedures.ProcedureStatus;
import com.agenticai.core.guardrails.procedures.dto.ProcedureRequest;
import com.agenticai.core.security.TenantContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Auto-improvement loop (Tier-3): turns recurring production failures into concrete, reviewable
 * fixes.
 *
 * <p>The clustering half already exists - {@code PlatformIssueService} fingerprint-dedups every
 * failure signal (tool errors, workflow FAILED runs, LLM-quality flags, provider fallbacks) into
 * one recurring row with an {@code occurrenceCount}. What was missing is the "so fix it" half: this
 * service takes the OPEN issue clusters keyed to one agent ({@code sourceId == agentId}, the
 * convention {@code TOOL_CALL}/ {@code LLM_QUALITY}/{@code PROVIDER_FALLBACK} already follow), asks
 * the LLM to distil each cluster into a suggested fix, and parks it as a PENDING {@link
 * AgentImprovementSuggestion} for the admin to accept or dismiss.
 *
 * <h2>Safety posture</h2>
 *
 * <ul>
 *   <li>Nothing auto-applies: a suggestion is inert until {@code accept}, and {@code suggestedText}
 *       carries the exact bytes that would be applied so the decision is reviewable.
 *   <li>Generation is bounded: at most {@link #MAX_SUGGESTIONS_PER_SCAN} new suggestions per call,
 *       and an issue cluster that already has a PENDING or ACCEPTED suggestion is never
 *       re-suggested.
 *   <li>Recurring failures only: a cluster needs {@link #MIN_OCCURRENCES} hits before it's worth a
 *       suggestion - a one-off failure is triage, not a pattern.
 *   <li>Accept is a thin, honest apply: {@code PROMPT_REVISION} goes through {@code
 *       AgentService#updateSystemPrompt} (same path as the admin console, version snapshot
 *       included); {@code NEW_PROCEDURE} creates a DRAFT procedure, attaches it, then activates it
 *       - all reversible via the existing procedure/agent admin surfaces.
 *   <li>LLM output is validated the same way {@code AgentGenerationService} does it - fenced-JSON
 *       tolerant parse, required-field checks; a malformed verdict for one cluster is skipped with
 *       a warning, not fatal to the scan.
 * </ul>
 */
@Service
public class AgentImprovementService {

    private static final Logger log = LoggerFactory.getLogger(AgentImprovementService.class);

    /** A failure cluster must recur at least this many times before it's a pattern worth fixing. */
    static final int MIN_OCCURRENCES = 2;

    /** Upper bound on LLM calls per scan - suggestions are batched, not a firehose. */
    static final int MAX_SUGGESTIONS_PER_SCAN = 3;

    // Package-private (not private): AgentHealthScoreService reuses this exact set so
    // "which issues count against this agent" can never drift between suggestion
    // generation and the health-score snapshot computed from the same clusters.
    static final Set<String> AGENT_KEYED_SOURCES =
            Set.of("TOOL_CALL", "LLM_QUALITY", "PROVIDER_FALLBACK");

    private static final String SUGGESTION_SYSTEM_PROMPT =
            """
        You improve agents on an AI-agent SaaS platform. You are given:
        - the agent's name and current system prompt,
        - one recurring production failure cluster (issue type, title,
          latest message, how many times it has occurred, and an AI
          diagnosis if one exists).

        Produce ONE JSON object (no markdown fences, no commentary) with
        exactly these fields:

        - "kind": "PROMPT_REVISION" if the right fix is to change the
          agent's instructions (wrong tone, bad answers, refusals,
          hallucination-shaped output), or "NEW_PROCEDURE" if the right
          fix is a deterministic business rule the agent must follow
          (tool mis-use, missing steps, process violations).
        - "title": one-line summary of the suggested fix (max 120 chars).
        - "rationale": 1-3 sentences tying the fix to the failure
          evidence.
        - "suggestedText": the FULL artifact to apply. For
          PROMPT_REVISION: the complete replacement system prompt (not a
          diff). For NEW_PROCEDURE: the procedure body as a short list of
          imperative rules, one per line (e.g. "Always verify the order
          total before issuing a refund.").

        Be conservative: if the evidence does not clearly indicate a
        prompt- or procedure-level fix (e.g. infrastructure flake),
        still return the object but set "title" to "NO_CLEAR_FIX" and
        leave "suggestedText" empty - the caller will skip it.

        Output the JSON object and nothing else.
        """;

    private final AgentImprovementSuggestionRepository suggestionRepository;
    private final PlatformIssueRepository issueRepository;
    private final AgentRepository agentRepository;
    private final AgentService agentService;
    private final ProcedureService procedureService;
    private final AssistService assistService;
    private final ObjectMapper objectMapper;

    public AgentImprovementService(
            AgentImprovementSuggestionRepository suggestionRepository,
            PlatformIssueRepository issueRepository,
            AgentRepository agentRepository,
            AgentService agentService,
            ProcedureService procedureService,
            AssistService assistService,
            ObjectMapper objectMapper) {
        this.suggestionRepository = suggestionRepository;
        this.issueRepository = issueRepository;
        this.agentRepository = agentRepository;
        this.agentService = agentService;
        this.procedureService = procedureService;
        this.assistService = assistService;
        this.objectMapper = objectMapper;
    }

    public List<AgentImprovementSuggestion> listForAgent(UUID agentId) {
        return suggestionRepository.findTop100ByAgentIdOrderByCreatedAtDesc(agentId);
    }

    /**
     * Scan this agent's OPEN recurring issue clusters and distil each new one into a PENDING
     * suggestion. Returns the suggestions created this call (possibly empty - "no new patterns" is
     * a normal answer, not an error).
     */
    public List<AgentImprovementSuggestion> generateForAgent(UUID agentId) {
        return generateForAgent(agentId, false);
    }

    /**
     * {@link #generateForAgent(UUID)} for the hourly sweep ({@code background == true}): the
     * distillation call skips a provider key that was refused recently, and when that leaves
     * nothing the {@link com.agenticai.core.providers.ProviderCoolingDownException} propagates so
     * the sweep can stop asking this workspace's provider (prod 2026-10-05: 24 failed calls a day
     * against a rejected key, for two weeks).
     */
    public List<AgentImprovementSuggestion> generateForAgent(UUID agentId, boolean background) {
        Agent agent =
                agentRepository
                        .findById(agentId)
                        .orElseThrow(() -> new ImprovementNotFoundException("No agent " + agentId));
        String agentKey = agentId.toString();

        List<PlatformIssue> candidates =
                issueRepository.findTop200ByStatusOrderByLastSeenAtDesc(IssueStatus.OPEN).stream()
                        .filter(
                                i ->
                                        i.getSource() != null
                                                && AGENT_KEYED_SOURCES.contains(
                                                        i.getSource().name())
                                                && agentKey.equals(i.getSourceId())
                                                && i.getOccurrenceCount() >= MIN_OCCURRENCES
                                                && !suggestionRepository
                                                        .existsByAgentIdAndIssueIdAndStatusIn(
                                                                agentId,
                                                                i.getId(),
                                                                EnumSet.of(
                                                                        ImprovementStatus.PENDING,
                                                                        ImprovementStatus
                                                                                .ACCEPTED)))
                        .limit(MAX_SUGGESTIONS_PER_SCAN)
                        .toList();

        String tenantId = TenantContext.current().map(TenantContext::getTenantId).orElse(null);
        List<AgentImprovementSuggestion> created = new java.util.ArrayList<>();
        for (PlatformIssue issue : candidates) {
            try {
                AgentImprovementSuggestion suggestion = distill(agent, issue, tenantId, background);
                if (suggestion != null) {
                    created.add(suggestionRepository.save(suggestion));
                }
            } catch (com.agenticai.core.providers.ProviderCoolingDownException cooling) {
                throw cooling; // every remaining cluster would hit the same refused provider
            } catch (Exception e) {
                // One bad LLM verdict must not lose the rest of the scan.
                log.warn(
                        "Improvement distillation failed for issue {} - skipping",
                        issue.getId(),
                        e);
            }
        }
        return created;
    }

    /**
     * Apply the suggestion. PROMPT_REVISION replaces the agent's system prompt through the same
     * service the admin console uses; NEW_PROCEDURE creates + attaches + activates a procedure.
     */
    public AgentImprovementSuggestion accept(UUID suggestionId, String actorId) {
        AgentImprovementSuggestion suggestion = getPending(suggestionId);
        String appliedRef =
                switch (suggestion.getKind()) {
                    case PROMPT_REVISION -> {
                        agentService.updateSystemPrompt(
                                suggestion.getAgentId(), suggestion.getSuggestedText());
                        yield "systemPrompt";
                    }
                    case NEW_PROCEDURE -> {
                        Procedure procedure =
                                procedureService.create(
                                        new ProcedureRequest(
                                                truncate(suggestion.getTitle(), 255),
                                                suggestion.getRationale(),
                                                List.of(
                                                        new ProcedureRequest.RuleInput(
                                                                suggestion.getSuggestedText(),
                                                                null,
                                                                null,
                                                                null))),
                                        actorId);
                        procedureService.attach(suggestion.getAgentId(), procedure.getId(), 0);
                        procedureService.setStatus(procedure.getId(), ProcedureStatus.ACTIVE);
                        yield "procedure:" + procedure.getId();
                    }
                };
        suggestion.markAccepted(actorId, appliedRef);
        return suggestionRepository.save(suggestion);
    }

    public AgentImprovementSuggestion dismiss(UUID suggestionId, String actorId) {
        AgentImprovementSuggestion suggestion = getPending(suggestionId);
        suggestion.markDismissed(actorId);
        return suggestionRepository.save(suggestion);
    }

    private AgentImprovementSuggestion getPending(UUID suggestionId) {
        AgentImprovementSuggestion suggestion =
                suggestionRepository
                        .findById(suggestionId)
                        .orElseThrow(
                                () ->
                                        new ImprovementNotFoundException(
                                                "No suggestion " + suggestionId));
        if (suggestion.getStatus() != ImprovementStatus.PENDING) {
            throw new ImprovementNotFoundException(
                    "Suggestion " + suggestionId + " is already " + suggestion.getStatus());
        }
        return suggestion;
    }

    private AgentImprovementSuggestion distill(
            Agent agent, PlatformIssue issue, String tenantId, boolean background) {
        String context =
                """
            Agent name: %s
            Current system prompt:
            ---
            %s
            ---
            Recurring failure cluster:
            - issue type: %s
            - title: %s
            - latest message (AI diagnosis, when present, is appended):
            %s
            - occurrences: %d
            """
                        .formatted(
                                agent.getName(),
                                truncate(agent.getSystemPrompt(), 3000),
                                issue.getSource(),
                                issue.getTitle(),
                                truncate(issue.getMessage(), 2000),
                                issue.getOccurrenceCount());

        String raw =
                background
                        ? assistService.completeInBackground(SUGGESTION_SYSTEM_PROMPT, context)
                        : assistService.complete(SUGGESTION_SYSTEM_PROMPT, context);
        JsonNode spec = parseSpec(raw, issue.getId());
        if ("NO_CLEAR_FIX".equals(spec.path("title").asText(""))) {
            return null;
        }
        ImprovementKind kind;
        try {
            kind = ImprovementKind.valueOf(spec.path("kind").asText("").trim());
        } catch (IllegalArgumentException e) {
            log.warn("Improvement verdict for issue {} had unknown kind - skipping", issue.getId());
            return null;
        }
        String suggestedText = spec.path("suggestedText").asText("").trim();
        if (suggestedText.isEmpty()) {
            return null;
        }
        String title =
                spec.path("title").asText("Suggested fix for recurring " + issue.getSource());
        return new AgentImprovementSuggestion(
                tenantId,
                agent.getId(),
                issue.getId(),
                kind,
                truncate(title, 255),
                spec.path("rationale").asText(null),
                suggestedText);
    }

    private JsonNode parseSpec(String raw, UUID issueId) {
        // Markdown fences / prose around the JSON are tolerated by StructuredOutput;
        // a missing or non-object verdict is the same failure either way.
        return StructuredOutput.readTree(objectMapper, raw)
                .filter(JsonNode::isObject)
                .orElseThrow(
                        () ->
                                new ImprovementGenerationException(
                                        "Unparseable improvement verdict for issue " + issueId));
    }

    private static String truncate(String value, int max) {
        return value == null || value.length() <= max ? value : value.substring(0, max);
    }
}
