package com.agenticai.core.knowledge.ocr;

import com.agenticai.core.knowledge.ocr.dto.OcrProviderActivationRequest;
import com.agenticai.core.knowledge.ocr.dto.OcrProviderStatusResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Tenant-admin BYOK config for cloud OCR - same three-endpoint
 * activate/status/deactivate shape as {@code SarvamController}.
 */
@RestController
@RequestMapping("/api/knowledge/ocr-provider")
public class OcrProviderConfigController {

    private final OcrProviderConfigService service;

    public OcrProviderConfigController(OcrProviderConfigService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<Void> activate(@Valid @RequestBody OcrProviderActivationRequest request) {
        service.activate(request.provider(), request.apiKey());
        return ResponseEntity.status(HttpStatus.CREATED).build();
    }

    @GetMapping("/status")
    public ResponseEntity<OcrProviderStatusResponse> status() {
        return ResponseEntity.ok(new OcrProviderStatusResponse(service.currentProvider().orElse(null)));
    }

    @DeleteMapping
    public ResponseEntity<Void> deactivate() {
        service.deactivate();
        return ResponseEntity.noContent().build();
    }
}
