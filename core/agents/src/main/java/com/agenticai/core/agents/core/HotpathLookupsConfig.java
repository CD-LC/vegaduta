package com.agenticai.core.agents.core;

import com.agenticai.core.security.HotpathLookups;
import jakarta.annotation.PostConstruct;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Turns on {@link HotpathLookups} from {@code app.chat.hotpath.config-lookup-ttl} (default 3 s, max
 * 30 s, 0 = off).
 */
@Component
class HotpathLookupsConfig {

    private final Duration ttl;

    HotpathLookupsConfig(@Value("${app.chat.hotpath.config-lookup-ttl:3s}") Duration ttl) {
        this.ttl = AgentProjectionCache.clamp(ttl);
    }

    @PostConstruct
    void configure() {
        HotpathLookups.configure(ttl);
    }
}
