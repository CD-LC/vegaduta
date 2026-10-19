package com.agenticai.core.devices.sentinel;

import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Mobile Sentinels API (the Mobile Sentinels design (opt-in sync, privacy-first defaults; described below)).
 * Signed-in user JWT on every path (falls through to
 * {@code anyRequest().authenticated()} in SecurityConfig); admin gating and
 * own-data scoping live in {@link MobileSentinelService}.
 */
@RestController
@RequestMapping("/api/mobile/sentinels")
public class MobileSentinelController {

    private final MobileSentinelService service;

    public MobileSentinelController(MobileSentinelService service) {
        this.service = service;
    }

    @GetMapping("/policy")
    public MobileSentinelService.PolicyView policy() {
        return service.getPolicy();
    }

    /** Tenant admin or platform admin only. */
    @PutMapping("/policy")
    public MobileSentinelService.PolicyView updatePolicy(@RequestBody MobileSentinelService.PolicyRequest request) {
        return service.updatePolicy(request);
    }

    @PostMapping("/events")
    public MobileSentinelService.IngestResult ingest(@RequestBody MobileSentinelService.IngestRequest request) {
        return service.ingest(request);
    }

    /** The caller's own events, newest first. */
    @GetMapping("/events")
    public Map<String, List<MobileSentinelService.EventView>> events(
        @RequestParam(required = false) Integer limit, @RequestParam(required = false) String sentinelId
    ) {
        return Map.of("events", service.listOwn(limit, sentinelId));
    }

    /** Right to erasure: deletes every event the caller has synced. */
    @DeleteMapping("/events")
    public Map<String, Integer> erase() {
        return Map.of("deleted", service.eraseOwn());
    }

    /** Aggregate counts only - no titles, summaries or user ids. */
    @GetMapping("/admin/summary")
    public MobileSentinelService.AdminSummary adminSummary() {
        return service.adminSummary();
    }

    @ExceptionHandler(SentinelSyncDisabledException.class)
    public ResponseEntity<Map<String, String>> syncDisabled() {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "sentinel-sync-disabled"));
    }
}
