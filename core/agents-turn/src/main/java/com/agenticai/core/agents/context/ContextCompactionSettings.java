package com.agenticai.core.agents.context;

import com.agenticai.core.settings.SettingContext;
import com.agenticai.core.settings.SettingDefinition;
import com.agenticai.core.settings.SettingRisk;
import com.agenticai.core.settings.SettingScope;
import com.agenticai.core.settings.SettingType;
import com.agenticai.core.settings.SettingUnit;
import com.agenticai.core.settings.SettingsContributor;
import com.agenticai.core.settings.SettingsResolver;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;

/**
 * When a long prompt is compacted, as workspace and agent settings: the share of the model's context window at which
 * old tool results become restorable placeholders ({@code chat.compaction.clearAt}), the share at which the oldest
 * turns become one summary ({@code chat.compaction.compactAt}) and how many of the newest tool results are never
 * cleared ({@code chat.compaction.keepRecentToolResults}). Lower shares keep prompts smaller (fewer tokens, a
 * faster first token) at the price of earlier loss of detail; higher shares keep more detail and cost more.
 *
 * <p>The platform values are the {@code app.context.compaction.*} keys {@link ChatContextCompaction} reads with
 * {@code @Value}; the defaults here are the same constants. Read once per run in {@link #thresholds}. The master
 * switch and the estimate constants stay with the deployment.
 */
public class ContextCompactionSettings implements SettingsContributor {

    private static final Logger log = LoggerFactory.getLogger(ContextCompactionSettings.class);

    public static final String CLEAR_AT = "chat.compaction.clearAt";
    public static final String COMPACT_AT = "chat.compaction.compactAt";
    public static final String KEEP_RECENT_TOOL_RESULTS = "chat.compaction.keepRecentToolResults";

    private static final SettingScope[] TENANT_AND_AGENT = {SettingScope.TENANT, SettingScope.AGENT};

    /** The thresholds of one compaction run. */
    record Thresholds(double clearAt, double compactAt, int keepRecentToolResults) {}

    private final Environment env;

    public ContextCompactionSettings(Environment env) {
        this.env = env;
    }

    @Override
    public List<SettingDefinition> definitions() {
        return List.of(
                SettingDefinition.builder(CLEAR_AT, "chat", SettingType.DECIMAL)
                        .range(0.1, 0.95).risk(SettingRisk.MEDIUM).scopes(TENANT_AND_AGENT)
                        .platform(() -> env.getProperty("app.context.compaction.clear-at", Double.class,
                                ContextCompactionProperties.DEFAULT_CLEAR_AT))
                        .property("app.context.compaction.clear-at").docsAnchor("behaviour-settings").build(),
                SettingDefinition.builder(COMPACT_AT, "chat", SettingType.DECIMAL)
                        .range(0.2, 0.98).risk(SettingRisk.MEDIUM).scopes(TENANT_AND_AGENT)
                        .platform(() -> env.getProperty("app.context.compaction.compact-at", Double.class,
                                ContextCompactionProperties.DEFAULT_COMPACT_AT))
                        .property("app.context.compaction.compact-at").docsAnchor("behaviour-settings").build(),
                SettingDefinition.builder(KEEP_RECENT_TOOL_RESULTS, "chat", SettingType.INT)
                        .unit(SettingUnit.COUNT).range(0, 50).risk(SettingRisk.LOW).scopes(TENANT_AND_AGENT)
                        .platform(() -> env.getProperty("app.context.compaction.keep-recent-tool-results",
                                Integer.class, ContextCompactionProperties.DEFAULT_KEEP_RECENT_TOOL_RESULTS))
                        .property("app.context.compaction.keep-recent-tool-results").docsAnchor("behaviour-settings").build());
    }

    /**
     * The thresholds for a run of {@code agentId} in the calling thread's workspace: the agent's, the workspace's, else
     * {@code platform}. With no catalog or a failing read it is {@code platform} itself, so a settings outage never
     * changes when a prompt is compacted. A clear share above the compact share is lowered to it (clearing always
     * comes first).
     */
    static Thresholds thresholds(SettingsResolver catalog, UUID agentId, Thresholds platform) {
        if (catalog == null) {
            return platform;
        }
        try {
            SettingContext ctx = agentId == null ? SettingContext.tenant() : SettingContext.forAgent(agentId);
            double compactAt = catalog.getDecimal(COMPACT_AT, ctx);
            double clearAt = Math.min(catalog.getDecimal(CLEAR_AT, ctx), compactAt);
            int keep = Math.max(0, catalog.getInt(KEEP_RECENT_TOOL_RESULTS, ctx));
            return new Thresholds(clearAt, compactAt, keep);
        } catch (RuntimeException e) {
            log.warn("Could not read the chat.compaction.* settings; using app.context.compaction.*: {}", e.toString());
            return platform;
        }
    }
}
