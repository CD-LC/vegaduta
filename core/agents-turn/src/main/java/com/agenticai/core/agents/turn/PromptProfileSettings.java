package com.agenticai.core.agents.turn;

import com.agenticai.core.settings.SettingContext;
import com.agenticai.core.settings.SettingDefinition;
import com.agenticai.core.settings.SettingRisk;
import com.agenticai.core.settings.SettingScope;
import com.agenticai.core.settings.SettingType;
import com.agenticai.core.settings.SettingsContributor;
import com.agenticai.core.settings.SettingsResolver;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * The catalog entry {@code chat.prompt.profile} ({@link PromptProfile}) over {@code
 * app.chat.prompt-profile}, settable per workspace and per agent, and the two places a turn applies
 * it: which tools it offers ({@link #ownTools}) and which prompt blocks it sends ({@link
 * ChatSystemPromptComposer#forProfile}).
 */
@Component
public class PromptProfileSettings implements SettingsContributor {

    private static final Logger log = LoggerFactory.getLogger(PromptProfileSettings.class);

    static final String KEY = "chat.prompt.profile";

    /**
     * The tools the platform offers on its own account, which {@link PromptProfile#BARE} leaves
     * out. {@code search_tools} and {@code call_tool} are not here: when they are on the list the
     * agent's own tools sit behind them.
     */
    static final Set<String> STANDING_TOOLS =
            Set.of(
                    "remember_fact",
                    "recall_related_facts",
                    "restore_tool_result",
                    "load_skill",
                    "read_skill_file",
                    "scratchpad_write",
                    "scratchpad_read",
                    "scratchpad_list",
                    "scratchpad_delete");

    private static volatile PromptProfileSettings current;

    private final PromptProfile platform;
    private final ObjectProvider<SettingsResolver> catalog;

    public PromptProfileSettings(
            @Value("${app.chat.prompt-profile:full}") String platform,
            ObjectProvider<SettingsResolver> catalog) {
        this.platform = PromptProfile.parse(platform);
        this.catalog = catalog;
        current = this;
    }

    @Override
    public List<SettingDefinition> definitions() {
        return List.of(
                SettingDefinition.builder(KEY, "chat", SettingType.ENUM)
                        .enumValues("full", "lean", "bare")
                        .risk(SettingRisk.MEDIUM)
                        .scopes(SettingScope.TENANT, SettingScope.AGENT)
                        .platform(platform::json)
                        .property("app.chat.prompt-profile")
                        .docsAnchor("behaviour-settings")
                        .build());
    }

    /** The profile {@code agentId}'s turn runs under; the platform value when the catalog cannot say. */
    public PromptProfile forAgent(UUID agentId) {
        SettingsResolver resolver = catalog == null ? null : catalog.getIfAvailable();
        if (resolver == null) {
            return platform;
        }
        try {
            SettingContext ctx =
                    agentId == null ? SettingContext.tenant() : SettingContext.forAgent(agentId);
            return PromptProfile.parse(resolver.getString(KEY, ctx));
        } catch (RuntimeException e) {
            log.warn("Could not read {}; using app.chat.prompt-profile: {}", KEY, e.toString());
            return platform;
        }
    }

    /** The profile of {@code agentId}'s turn; {@link PromptProfile#FULL} before the bean exists. */
    static PromptProfile of(UUID agentId) {
        PromptProfileSettings settings = current;
        return settings == null ? PromptProfile.FULL : settings.forAgent(agentId);
    }

    /**
     * What a bare turn offers: the tools the owner chose. With an allow-list on the agent or tool
     * servers attached to it, that is {@code tools} without the platform's standing tools; with
     * neither, every tool on the list is a platform default, so only {@code keep} (tools the caller
     * added for this run) stays.
     */
    static List<ToolCallback> ownTools(
            List<ToolCallback> tools, boolean ownerChoseTools, List<ToolCallback> keep) {
        if (tools == null) {
            return null;
        }
        List<ToolCallback> own = new ArrayList<>(tools.size());
        for (ToolCallback tool : tools) {
            boolean kept = keep != null && keep.contains(tool);
            if (kept
                    || (ownerChoseTools
                            && !STANDING_TOOLS.contains(tool.getToolDefinition().name()))) {
                own.add(tool);
            }
        }
        return own;
    }
}
