package com.agenticai.core.sdlc;

import com.agenticai.core.sdlc.dto.E2bCodeExecutionTemplateRequest;
import com.agenticai.core.sdlc.dto.E2bCredentialActivationRequest;
import com.agenticai.core.sdlc.dto.E2bCredentialResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Same three-endpoint shape {@code SarvamController} uses - activate/current/deactivate, nothing more in this scaffold pass. */
@RestController
@RequestMapping("/api/sdlc/credential")
public class E2bCredentialController {

    private final E2bCredentialService credentialService;

    public E2bCredentialController(E2bCredentialService credentialService) {
        this.credentialService = credentialService;
    }

    @PostMapping
    public ResponseEntity<E2bCredentialResponse> activate(@Valid @RequestBody E2bCredentialActivationRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(credentialService.activate(request));
    }

    @GetMapping("/status")
    public ResponseEntity<E2bCredentialResponse> status() {
        return credentialService.current()
            .map(ResponseEntity::ok)
            .orElseGet(() -> ResponseEntity.noContent().build());
    }

    @DeleteMapping
    public ResponseEntity<Void> deactivate() {
        credentialService.deactivate();
        return ResponseEntity.noContent().build();
    }

    /** Master Roadmap 2026 item #14 - records the tenant-built E2B template ID that exposes core/sdlc/e2b-template/'s {@code /run} HTTP contract. */
    @PutMapping("/code-execution-template")
    public ResponseEntity<E2bCredentialResponse> setCodeExecutionTemplate(@RequestBody E2bCodeExecutionTemplateRequest request) {
        return ResponseEntity.ok(credentialService.configureCodeExecutionTemplate(request.templateId()));
    }
}
