package com.agenticai.core.knowledge;

import com.agenticai.core.settings.SettingDefinition;
import com.agenticai.core.settings.SettingRisk;
import com.agenticai.core.settings.SettingScope;
import com.agenticai.core.settings.SettingType;
import com.agenticai.core.settings.SettingUnit;
import com.agenticai.core.settings.SettingsContributor;
import java.util.List;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Settings-catalog entries for knowledge search ({@code knowledge.search.*}). The platform values are read from
 * {@link KnowledgeSearchProperties}, the object {@link KnowledgeSearchService} reads, so the catalog default and the
 * runtime default can never differ. A search has no agent in hand (the caller's own top-k and minimum score are the
 * agent's {@code agent.limits.retrievalTopK} / {@code retrievalMinScore}), so these are workspace-level.
 */
@Component
@Profile("knowledge")
public class KnowledgeSettings implements SettingsContributor {

    public static final String CANDIDATE_POOL_MULTIPLIER = "knowledge.search.candidatePoolMultiplier";
    public static final String CANDIDATE_POOL_MAX = "knowledge.search.candidatePoolMax";

    private final KnowledgeSearchProperties properties;

    public KnowledgeSettings(KnowledgeSearchProperties properties) {
        this.properties = properties;
    }

    @Override
    public List<SettingDefinition> definitions() {
        return List.of(
                SettingDefinition.builder(CANDIDATE_POOL_MULTIPLIER, "knowledge", SettingType.INT)
                        .unit(SettingUnit.COUNT).range(1, 20).risk(SettingRisk.LOW).scopes(SettingScope.TENANT)
                        .platform(properties::getCandidatePoolMultiplier)
                        .property("app.knowledge.search.candidate-pool-multiplier").docsAnchor("behaviour-settings").build(),
                SettingDefinition.builder(CANDIDATE_POOL_MAX, "knowledge", SettingType.INT)
                        .unit(SettingUnit.COUNT).range(5, 500).risk(SettingRisk.MEDIUM).scopes(SettingScope.TENANT)
                        .platform(properties::getCandidatePoolMax)
                        .property("app.knowledge.search.candidate-pool-max").docsAnchor("behaviour-settings").build());
    }
}
