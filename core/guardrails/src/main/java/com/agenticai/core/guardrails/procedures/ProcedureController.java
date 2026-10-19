package com.agenticai.core.guardrails.procedures;

import com.agenticai.core.guardrails.procedures.dto.AttachProcedureRequest;
import org.springframework.web.bind.annotation.RequestParam;
import com.agenticai.core.tenancy.ListPaging;
import com.agenticai.core.guardrails.procedures.dto.ProcedureRequest;
import com.agenticai.core.guardrails.procedures.dto.ProcedureResponse;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Tenant-facing CRUD + attachment surface for the procedures layer.
 * {@code /api/procedures/**} manages the SOP documents themselves;
 * {@code /api/agents/{agentId}/procedures/**} manages which agents a
 * procedure binds to. Writes are gated to {@code PERM_MANAGE_GUARDRAILS} in
 * SecurityConfig (procedures are enforcement rules - same permission class
 * as tenant guardrail rules); reads stay authenticated like every other
 * console list.
 */
@RestController
public class ProcedureController {

    private final ProcedureService service;

    public ProcedureController(ProcedureService service) {
        this.service = service;
    }

    @GetMapping("/api/procedures")
    public ResponseEntity<List<ProcedureResponse>> list(@RequestParam(required = false) Integer limit, @RequestParam(required = false) Long offset) {
        // Window first so only the requested page pays the per-row rule resolution.
        var all = service.listAll();
        int size = ListPaging.limit(limit, 50, 200);
        long skip = ListPaging.offset(offset);
        int from = (int) Math.min(skip, all.size());
        int to = (int) Math.min((long) from + size, all.size());
        var page = all.subList(from, to).stream()
            .map(p -> ProcedureResponse.from(p, service.getResolved(p.getId()).rules()))
            .toList();
        return ResponseEntity.ok()
            .header("X-Total-Count", String.valueOf(all.size()))
            .header("X-Has-More", String.valueOf(to < all.size()))
            .body(page);
    }

    @GetMapping("/api/procedures/{id}")
    public ProcedureResponse get(@PathVariable UUID id) {
        ProcedureService.ResolvedProcedure resolved = service.getResolved(id);
        return ProcedureResponse.from(resolved.procedure(), resolved.rules());
    }

    @PostMapping("/api/procedures")
    public ResponseEntity<ProcedureResponse> create(
        @Valid @RequestBody ProcedureRequest request, Authentication authentication
    ) {
        Procedure created = service.create(request, callerSubject(authentication));
        ProcedureService.ResolvedProcedure resolved = service.getResolved(created.getId());
        return ResponseEntity.status(HttpStatus.CREATED)
            .body(ProcedureResponse.from(resolved.procedure(), resolved.rules()));
    }

    @PutMapping("/api/procedures/{id}")
    public ProcedureResponse update(@PathVariable UUID id, @Valid @RequestBody ProcedureRequest request) {
        Procedure updated = service.update(id, request);
        ProcedureService.ResolvedProcedure resolved = service.getResolved(updated.getId());
        return ProcedureResponse.from(resolved.procedure(), resolved.rules());
    }

    /** Lifecycle transitions are explicit endpoints, not a PATCHed status field - an audit-clean verb per transition. */
    @PostMapping("/api/procedures/{id}/activate")
    public ProcedureResponse activate(@PathVariable UUID id) {
        Procedure activated = service.setStatus(id, ProcedureStatus.ACTIVE);
        ProcedureService.ResolvedProcedure resolved = service.getResolved(activated.getId());
        return ProcedureResponse.from(resolved.procedure(), resolved.rules());
    }

    @PostMapping("/api/procedures/{id}/archive")
    public ProcedureResponse archive(@PathVariable UUID id) {
        Procedure archived = service.setStatus(id, ProcedureStatus.ARCHIVED);
        ProcedureService.ResolvedProcedure resolved = service.getResolved(archived.getId());
        return ProcedureResponse.from(resolved.procedure(), resolved.rules());
    }

    @DeleteMapping("/api/procedures/{id}")
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        service.delete(id);
        return ResponseEntity.noContent().build();
    }

    // --- Agent attachment ---

    @GetMapping("/api/agents/{agentId}/procedures")
    public List<ProcedureResponse> listForAgent(@PathVariable UUID agentId) {
        return service.listForAgent(agentId).stream()
            .map(r -> ProcedureResponse.from(r.procedure(), r.rules()))
            .toList();
    }

    @PostMapping("/api/agents/{agentId}/procedures")
    public ResponseEntity<Void> attach(
        @PathVariable UUID agentId, @Valid @RequestBody AttachProcedureRequest request
    ) {
        service.attach(agentId, request.procedureId(), request.position() != null ? request.position() : 0);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/api/agents/{agentId}/procedures/{procedureId}")
    public ResponseEntity<Void> detach(@PathVariable UUID agentId, @PathVariable UUID procedureId) {
        service.detach(agentId, procedureId);
        return ResponseEntity.noContent().build();
    }

    // --- Controller-local exception mapping (same pattern as GuardrailRuleController) ---

    @ExceptionHandler(ProcedureNotFoundException.class)
    public ResponseEntity<Map<String, Object>> handleNotFound(ProcedureNotFoundException e) {
        return error(HttpStatus.NOT_FOUND, e.getMessage());
    }

    @ExceptionHandler(ProcedureValidationException.class)
    public ResponseEntity<Map<String, Object>> handleValidation(ProcedureValidationException e) {
        return error(HttpStatus.BAD_REQUEST, e.getMessage());
    }

    private String callerSubject(Authentication authentication) {
        return authentication != null ? authentication.getName() : null;
    }

    private ResponseEntity<Map<String, Object>> error(HttpStatus status, String message) {
        return ResponseEntity.status(status).body(Map.of(
            "status", status.value(),
            "error", message
        ));
    }
}
