package com.agenticai.core.guardrails;

import org.springframework.web.bind.annotation.RequestParam;
import com.agenticai.core.tenancy.ListPaging;
import com.agenticai.core.guardrails.dto.GuardrailRuleRequest;
import org.springframework.web.bind.annotation.RequestParam;
import com.agenticai.core.tenancy.ListPaging;
import com.agenticai.core.guardrails.dto.GuardrailRuleResponse;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Tenant-facing CRUD surface for {@link GuardrailRule}. Reads are open to
 * every plan (including the platform {@link GuardrailRuleType#DEFAULT_TEMPLATE}
 * presets - FREE tenants keep full read/use access to those). Writes
 * (create/update/delete) are gated by {@link GuardrailRuleService}:
 * authoring a {@link GuardrailRuleType#CUSTOM} rule is a PREMIUM/UNLIMITED
 * feature, and a {@code DEFAULT_TEMPLATE} row is never editable by anyone -
 * both gates throw from the service layer and are handled below (the
 * not-found/immutable cases locally, same "controller-local exception
 * mapping precedes the app-wide advice" pattern {@code
 * ModerationReviewController} already establishes in this module; the
 * FREE-tier {@code TenantLimitExceededException} needs no local handler at
 * all - it's already mapped to HTTP 403 by the shared {@code
 * GlobalExceptionHandler} in {@code core/app}, the same handler {@code
 * AgentService}'s equivalent gate relies on).
 */
@RestController
@RequestMapping("/api/guardrails/rules")
public class GuardrailRuleController {

    private final GuardrailRuleService service;

    public GuardrailRuleController(GuardrailRuleService service) {
        this.service = service;
    }

    @GetMapping
    public ResponseEntity<List<GuardrailRuleResponse>> list(@RequestParam(required = false) Integer limit, @RequestParam(required = false) Long offset) {
        return ListPaging.window(
            service.listAll().stream().map(GuardrailRuleResponse::from).toList(),
            limit, offset, 50, 500);
    }

    @GetMapping("/{id}")
    public GuardrailRuleResponse get(@PathVariable UUID id) {
        return GuardrailRuleResponse.from(service.get(id));
    }

    @PostMapping
    public ResponseEntity<GuardrailRuleResponse> create(@Valid @RequestBody GuardrailRuleRequest request) {
        GuardrailRuleResponse created = GuardrailRuleResponse.from(service.createCustom(request));
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @PutMapping("/{id}")
    public GuardrailRuleResponse update(@PathVariable UUID id, @Valid @RequestBody GuardrailRuleRequest request) {
        return GuardrailRuleResponse.from(service.update(id, request));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        service.delete(id);
        return ResponseEntity.noContent().build();
    }

    // --- Controller-local exception mapping (precedes the app-wide advice) ---

    @ExceptionHandler(GuardrailRuleNotFoundException.class)
    public ResponseEntity<Map<String, Object>> handleNotFound(GuardrailRuleNotFoundException e) {
        return error(HttpStatus.NOT_FOUND, e.getMessage());
    }

    @ExceptionHandler(GuardrailRuleImmutableException.class)
    public ResponseEntity<Map<String, Object>> handleImmutable(GuardrailRuleImmutableException e) {
        return error(HttpStatus.FORBIDDEN, e.getMessage());
    }

    private ResponseEntity<Map<String, Object>> error(HttpStatus status, String message) {
        return ResponseEntity.status(status).body(Map.of(
            "status", status.value(),
            "error", message
        ));
    }
}
