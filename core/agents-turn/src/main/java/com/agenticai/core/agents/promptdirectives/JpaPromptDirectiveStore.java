package com.agenticai.core.agents.promptdirectives;

import com.agenticai.core.agents.turn.PromptDirectiveSettings;
import com.agenticai.core.agents.turn.ResolvedDirectives;
import java.util.HashMap;
import java.util.Map;
import org.springframework.stereotype.Component;

/** Reads the current tenant's stored directive settings for the chat path's cache. */
@Component
class JpaPromptDirectiveStore implements PromptDirectiveSettings.Store {

    private final PromptDirectiveSettingRepository repository;

    JpaPromptDirectiveStore(PromptDirectiveSettingRepository repository) {
        this.repository = repository;
    }

    @Override
    public Map<String, ResolvedDirectives.Setting> loadAllForCurrentTenant() {
        Map<String, ResolvedDirectives.Setting> out = new HashMap<>();
        for (PromptDirectiveSetting row : repository.findAll()) {
            out.put(
                    row.getDirectiveKey(),
                    new ResolvedDirectives.Setting(row.isEnabled(), row.getTextOverride()));
        }
        return out;
    }
}
