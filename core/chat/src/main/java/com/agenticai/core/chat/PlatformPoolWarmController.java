package com.agenticai.core.chat;

import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code POST /api/platform-llm/warm}: any signed-in user may ask for the platform model to be
 * loaded ahead of their first chat (see {@link PlatformPoolWarmer}). Always 202 and returns at
 * once; {@code started} says whether this call started a warm-up or one was recent or running.
 */
@RestController
@RequestMapping(value = "/api/platform-llm", produces = MediaType.APPLICATION_JSON_VALUE)
public class PlatformPoolWarmController {

    private final PlatformPoolWarmer warmer;

    public PlatformPoolWarmController(PlatformPoolWarmer warmer) {
        this.warmer = warmer;
    }

    @PostMapping("/warm")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<Map<String, Boolean>> warm() {
        return ResponseEntity.accepted().body(Map.of("started", warmer.warmAsync()));
    }
}
