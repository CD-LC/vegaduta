package com.agenticai.core.agents.limits;

import com.agenticai.core.agents.runtime.RuntimeLimits;
import com.agenticai.core.agents.runtime.RuntimeSetting;
import com.agenticai.core.settings.SettingScope;
import com.agenticai.core.settings.SettingsService;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * Puts a change made through the older {@code /runtime-limits} endpoints into the settings history, so the history of
 * an {@code agent.limits.*} key is complete whichever API changed it. A failure is logged and never fails the write
 * the person asked for.
 */
@Component
public class RuntimeLimitsHistory {

    private static final Logger log = LoggerFactory.getLogger(RuntimeLimitsHistory.class);

    private final ObjectProvider<SettingsService> settings;

    public RuntimeLimitsHistory(ObjectProvider<SettingsService> settings) {
        this.settings = settings;
    }

    void record(SettingScope scope, UUID agentId, RuntimeLimits before, RuntimeLimits after, String source) {
        SettingsService service = settings.getIfAvailable();
        if (service == null) {
            return;
        }
        try {
            service.recordExternalChange(scope, agentId, toMap(before), toMap(after), source);
        } catch (RuntimeException e) {
            log.warn("Could not record the settings history of a runtime-limits change: {}", e.toString());
        }
    }

    private static Map<String, Object> toMap(RuntimeLimits limits) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (limits != null) {
            for (RuntimeSetting s : RuntimeSetting.values()) {
                Number n = limits.get(s);
                if (n != null) {
                    out.put(RuntimeLimitsSettings.key(s), n);
                }
            }
        }
        return out;
    }
}
