package com.agenticai.core.graphmemory;

import com.agenticai.core.settings.SettingDefinition;
import com.agenticai.core.settings.SettingRisk;
import com.agenticai.core.settings.SettingScope;
import com.agenticai.core.settings.SettingType;
import com.agenticai.core.settings.SettingUnit;
import com.agenticai.core.settings.SettingsContributor;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Settings-catalog entries for what an agent recalls from its graph memory ({@code memory.graph.*}). The platform
 * values are read from {@link GraphMemoryProperties}, the object {@link GraphMemoryService} reads, so the catalog
 * default and the runtime default can never differ.
 */
@Component
public class GraphMemorySettings implements SettingsContributor {

    public static final String RECALL_MAX_FACTS = "memory.graph.recallMaxFacts";
    public static final String RECALL_MAX_PATHS = "memory.graph.recallMaxPaths";

    private final GraphMemoryProperties properties;

    public GraphMemorySettings(GraphMemoryProperties properties) {
        this.properties = properties;
    }

    @Override
    public List<SettingDefinition> definitions() {
        return List.of(
                SettingDefinition.builder(RECALL_MAX_FACTS, "memory", SettingType.INT)
                        .unit(SettingUnit.COUNT).range(1, 100).risk(SettingRisk.LOW)
                        .scopes(SettingScope.TENANT, SettingScope.AGENT)
                        .platform(() -> properties.getRecall().getMaxFacts())
                        .property("app.graph-memory.recall.max-facts").docsAnchor("behaviour-settings").build(),
                SettingDefinition.builder(RECALL_MAX_PATHS, "memory", SettingType.INT)
                        .unit(SettingUnit.COUNT).range(10, 2_000).risk(SettingRisk.MEDIUM)
                        .scopes(SettingScope.TENANT, SettingScope.AGENT)
                        .platform(() -> properties.getRecall().getMaxPaths())
                        .property("app.graph-memory.recall.max-paths").docsAnchor("behaviour-settings").build());
    }
}
