package com.agenticai.core.agents.learning.recipes;

import com.agenticai.core.agents.learning.LearningProposal;
import com.agenticai.core.agents.learning.LearningProposalApplier;
import com.agenticai.core.agents.learning.LearningProposalApplyException;
import com.agenticai.core.agents.learning.LearningProposalKind;
import com.agenticai.core.agents.tools.DesktopRecipeSavePort;
import com.agenticai.core.json.JsonMappers;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Turns an approved {@link LearningProposalKind#DESKTOP_RECIPE} proposal into a saved desktop
 * recipe through {@link DesktopRecipeSavePort} (the port agents-tools implements). The recipe is
 * rebuilt from the run journal - the proposal's own text is only the recipe's name, which the
 * person can edit before approving.
 */
@Component
public class DesktopRecipeApplier implements LearningProposalApplier {

    private static final Logger log = LoggerFactory.getLogger(DesktopRecipeApplier.class);

    private final DesktopRecipeSavePort recipes;

    public DesktopRecipeApplier(org.springframework.beans.factory.ObjectProvider<DesktopRecipeSavePort> recipes) {
        this.recipes = recipes.getIfAvailable();
    }

    @Override
    public LearningProposalKind kind() {
        return LearningProposalKind.DESKTOP_RECIPE;
    }

    @Override
    public void apply(LearningProposal proposal, String text) throws LearningProposalApplyException {
        JsonNode payload;
        try {
            payload = JsonMappers.plain().readTree(proposal.getPayload());
        } catch (Exception e) {
            throw new LearningProposalApplyException("the proposal's saved run reference is unreadable");
        }
        UUID agentId;
        try {
            agentId = UUID.fromString(payload.path("agentId").asText(""));
        } catch (IllegalArgumentException e) {
            agentId = null;
        }
        String sessionId = payload.path("sessionId").asText(null);
        if (agentId == null || sessionId == null || sessionId.isBlank()) {
            throw new LearningProposalApplyException("the proposal does not name the run it came from");
        }
        if (recipes == null) {
            throw new LearningProposalApplyException("desktop recipes are not available on this install");
        }
        String name = text == null || text.isBlank() ? null : text.strip();
        Optional<UUID> saved = recipes.saveRunAsRecipe(agentId, sessionId, name);
        if (saved.isEmpty()) {
            throw new LearningProposalApplyException(
                    "that run could not be recorded as a recipe - its steps may be too old or held nothing recordable");
        }
        log.info("Desktop-recipe proposal {} applied: recipe {}", proposal.getId(), saved.get());
    }
}
