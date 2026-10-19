package com.agenticai.core.agents.chatport;

import com.agenticai.core.agents.chatport.ChatTranscriptService.Transcript;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** See {@link ChatTranscriptService}. Thin: access, ownership and mapping live in the service. */
@RestController
@RequestMapping("/api/agents/{agentId}/sessions/{sessionId}/transcript")
public class ChatTranscriptController {

    private final ChatTranscriptService service;

    public ChatTranscriptController(ChatTranscriptService service) {
        this.service = service;
    }

    @GetMapping
    public ResponseEntity<Transcript> transcript(@PathVariable UUID agentId, @PathVariable String sessionId) {
        // Somebody's conversation: never cached.
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.transcript(agentId, sessionId));
    }
}
