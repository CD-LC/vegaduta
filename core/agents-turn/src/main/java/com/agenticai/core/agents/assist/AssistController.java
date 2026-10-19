package com.agenticai.core.agents.assist;

import com.agenticai.core.agents.assist.dto.AssistCodeEditRequest;
import com.agenticai.core.agents.assist.dto.AssistRequest;
import com.agenticai.core.agents.assist.dto.AssistResponse;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The "Ask AI" helper's single endpoint - every description/prompt field
 * across the console points here (see {@code AskAiHelper.js}). No
 * tenant-admin gate: this only ever reads an active provider credential and
 * makes one throwaway completion, it never writes anything, so it sits under
 * the same {@code anyRequest().authenticated()} rule as ordinary reads in
 * {@code SecurityConfig}.
 */
@RestController
@RequestMapping("/api/assist")
public class AssistController {

    private final AssistService assistService;

    public AssistController(AssistService assistService) {
        this.assistService = assistService;
    }

    @PostMapping("/draft")
    public AssistResponse draft(@Valid @RequestBody AssistRequest request) {
        return assistService.draft(request);
    }

    /**
     * The Phone app studio's cloud edit turn (web/app/build/studio). It used
     * to ride {@code /draft}, whose form-field system prompt ("reply with ONLY
     * the finished text for that field") contradicted the studio's edit-block
     * contract that had to be smuggled into the user turn.
     */
    @PostMapping("/code-edit")
    public AssistResponse codeEdit(@Valid @RequestBody AssistCodeEditRequest request) {
        return assistService.codeEdit(request);
    }
}
