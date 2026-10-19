package com.agenticai.core.common.studioapp;

import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.RequestParam;
import com.agenticai.core.tenancy.ListPaging;
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
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Owner endpoints for an app's custom domains: {@code /api/studio-apps/{appId}/domains}. */
@RestController
@RequestMapping("/api/studio-apps/{appId}/domains")
public class StudioAppDomainController {

    private final StudioAppDomainService service;

    public StudioAppDomainController(StudioAppDomainService service) {
        this.service = service;
    }

    public record AddRequest(@Size(max = 253) String hostname) {
    }

    public record DomainView(
        String id, String hostname, String status, String lastError,
        String createdAt, String verifiedAt, String lastCheckedAt,
        StudioAppDomainService.DnsInstructions dns
    ) {
    }

    @GetMapping
    public ResponseEntity<List<DomainView>> list(@PathVariable String appId, @RequestParam(required = false) Integer limit, @RequestParam(required = false) Long offset) {
        return ListPaging.window(service.list(parseId(appId)).stream().map(this::view).toList(), limit, offset, 50, 200);
    }

    @PostMapping
    public ResponseEntity<?> add(@PathVariable String appId, @RequestBody AddRequest body) {
        StudioAppDomain d = service.add(parseId(appId), body == null ? null : body.hostname());
        return ResponseEntity.status(HttpStatus.CREATED).body(view(d));
    }

    @PostMapping("/{domainId}/verify")
    public DomainView verify(@PathVariable String appId, @PathVariable String domainId) {
        return view(service.verify(parseId(appId), parseId(domainId)));
    }

    @DeleteMapping("/{domainId}")
    public ResponseEntity<?> remove(@PathVariable String appId, @PathVariable String domainId) {
        service.remove(parseId(appId), parseId(domainId));
        return ResponseEntity.noContent().build();
    }

    @ExceptionHandler(StudioAppService.StudioAppException.class)
    public ResponseEntity<?> onDomainError(StudioAppService.StudioAppException e) {
        HttpStatus status = switch (e.getMessage()) {
            case "app_not_found", "domain_not_found", "bad_id" -> HttpStatus.NOT_FOUND;
            case "hostname_in_use" -> HttpStatus.CONFLICT;
            default -> HttpStatus.UNPROCESSABLE_ENTITY;
        };
        return ResponseEntity.status(status).body(Map.of("error", e.getMessage()));
    }

    private DomainView view(StudioAppDomain d) {
        return new DomainView(
            d.getId().toString(), d.getHostname(), d.getStatus().name(), d.getLastError(),
            d.getCreatedAt().toString(),
            d.getVerifiedAt() == null ? null : d.getVerifiedAt().toString(),
            d.getLastCheckedAt() == null ? null : d.getLastCheckedAt().toString(),
            service.instructionsFor(d));
    }

    private static UUID parseId(String raw) {
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException e) {
            throw new StudioAppService.StudioAppException("bad_id");
        }
    }
}
