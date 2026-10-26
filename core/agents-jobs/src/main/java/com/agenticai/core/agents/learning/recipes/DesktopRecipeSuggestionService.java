package com.agenticai.core.agents.learning.recipes;

import com.agenticai.core.agents.learning.LearningProposalKind;
import com.agenticai.core.agents.learning.LearningProposalService;
import com.agenticai.core.agents.learning.LearningProposalService.Proposal;
import com.agenticai.core.agents.skills.DesktopRunSuggestionGateway;
import com.agenticai.core.agents.skills.SkillLearningGateway.TurnOutcome;
import com.agenticai.core.agents.tools.ToolCallLog;
import com.agenticai.core.agents.tools.ToolCallLogService;
import com.agenticai.core.security.TenantContext;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/**
 * "Save this desktop run as a recipe?" - the loop that notices a finished chat turn ran several
 * {@code local_desktop_*} actions and files a {@link LearningProposalKind#DESKTOP_RECIPE}
 * proposal naming the session, so approving it materializes the recipe through {@code
 * DesktopRecipeSavePort}.
 *
 * <p>Deliberately read-only about the calls themselves: it counts successful actuating steps
 * ({@code open}/{@code activate}/{@code press}/{@code click}/{@code type}/{@code key}) and never
 * copies tool inputs or outputs into the proposal - typed text can carry secrets, and the saved
 * recipe is rebuilt from the run journal on approve, not from the proposal text. Replay rows
 * ({@code desktop_recipe_step/*}) are skipped: a run that came from a recipe is already a recipe.
 *
 * <p>Like every learning loop this files a PENDING proposal only - nothing durable changes
 * without the person's approve. Off unless {@code app.learning.proposals.enabled} and {@code
 * app.learning.desktop-recipes.enabled} (on in the personal profile).
 */
@Service
@ConditionalOnProperty(
        name = {"app.learning.proposals.enabled", "app.learning.desktop-recipes.enabled"},
        havingValue = "true")
public class DesktopRecipeSuggestionService implements DesktopRunSuggestionGateway.Observer {

    private static final Logger log = LoggerFactory.getLogger(DesktopRecipeSuggestionService.class);

    /** The actuating {@code local_desktop_*} tools a recipe can be made of (see DesktopRecipeRecorder). */
    private static final Set<String> RECIPE_ACTIONS =
            Set.of(
                    "local_desktop_open",
                    "local_desktop_activate",
                    "local_desktop_press",
                    "local_desktop_click",
                    "local_desktop_type",
                    "local_desktop_key");

    /** Fewer than this is a one-off gesture, not a procedure worth a proposal. */
    static final int MIN_ACTIONS = 2;

    private static final int MAX_EVIDENCE_ACTIONS = 12;

    private final ObjectProvider<LearningProposalService> proposals;
    private final ToolCallLogService toolCalls;

    public DesktopRecipeSuggestionService(
            ObjectProvider<LearningProposalService> proposals, ToolCallLogService toolCalls) {
        this.proposals = proposals;
        this.toolCalls = toolCalls;
    }

    @PostConstruct
    void install() {
        DesktopRunSuggestionGateway.install(this);
    }

    @PreDestroy
    void shutdown() {
        DesktopRunSuggestionGateway.install(null);
    }

    @Override
    public boolean enabled() {
        return proposals.getIfAvailable() != null;
    }

    /** Runs on the thread that finished the turn: one journal read + one proposal row. */
    @Override
    public void onTurn(TurnOutcome o) {
        LearningProposalService svc = proposals.getIfAvailable();
        if (svc == null
                || o == null
                || !o.completed()
                || o.agentId() == null
                || o.sessionId() == null
                || o.startedAt() == null) {
            return;
        }
        try {
            List<ToolCallLog> calls =
                    toolCalls.findSince(o.agentId(), o.sessionId(), o.startedAt()).stream()
                            .filter(
                                    c ->
                                            c.getStartedAt() != null
                                                    && !c.getStartedAt().isBefore(o.startedAt()))
                            .toList();
            List<String> actions =
                    calls.stream()
                            .filter(
                                    c ->
                                            c.getToolName() != null
                                                    && RECIPE_ACTIONS.contains(c.getToolName())
                                                    && c.getErrorMessage() == null)
                            .map(c -> c.getToolName().substring("local_desktop_".length()))
                            .toList();
            if (actions.size() < MIN_ACTIONS) {
                return;
            }
            String subject = o.scope().context().map(TenantContext::getSubject).orElse(null);
            if (subject == null || subject.isBlank()) {
                return;
            }
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("agentId", o.agentId().toString());
            payload.put("sessionId", o.sessionId());
            Map<String, Object> evidence = new LinkedHashMap<>();
            evidence.put("sessionId", o.sessionId());
            evidence.put("actions", actions.stream().limit(MAX_EVIDENCE_ACTIONS).toList());
            evidence.put("actionCount", actions.size());
            // The body doubles as the recipe name on apply: the person edits it before approving.
            String name = suggestedName(o.userMessage());
            svc.propose(
                    new Proposal(
                            subject,
                            o.agentId(),
                            LearningProposalKind.DESKTOP_RECIPE,
                            "Save this desktop run as a recipe?",
                            name,
                            payload,
                            evidence));
        } catch (RuntimeException e) {
            log.warn("Desktop-recipe suggestion failed for agent {}: {}", o.agentId(), e.getMessage());
        }
    }

    /** A short recipe name from the person's request; a generic one when there is nothing to take. */
    static String suggestedName(String userMessage) {
        if (userMessage == null) {
            return "Desktop run";
        }
        String first = userMessage.strip().lines().findFirst().orElse("").strip();
        first = first.replaceAll("[\"'`]+", "");
        if (first.length() > 48) {
            first = first.substring(0, 48).strip();
        }
        return first.isEmpty() ? "Desktop run" : first;
    }
}
