package com.agenticai.core.platform.ops;

import com.agenticai.core.platform.TenantDeletionController;
import com.agenticai.core.platform.ops.PlatformBroadcastService.AnnouncementState;
import com.agenticai.core.platform.ops.PlatformBroadcastService.AnnouncementUpdate;
import com.agenticai.core.platform.ops.PlatformBroadcastService.MaintenanceState;
import com.agenticai.core.platform.ops.PlatformBroadcastService.MaintenanceUpdate;
import com.agenticai.core.security.TenantContext;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Maintenance mode and the announcement banner for the platform console.
 * Role-gated by the blanket {@code /api/platform/** -> ROLE_platform-admin}
 * rule. Switching maintenance (which 503s every tenant) additionally needs
 * {@code X-Master-Key}, checked exactly as tenant deletion does
 * ({@link TenantDeletionController#authenticateMasterKey}); the banner does not.
 */
@RestController
@RequestMapping("/api/platform")
public class PlatformBroadcastController {

    private final PlatformBroadcastService broadcast;
    private final String masterKey;

    public PlatformBroadcastController(PlatformBroadcastService broadcast,
                                       @Value("${app.platform.master-key:}") String masterKey) {
        this.broadcast = broadcast;
        this.masterKey = masterKey;
    }

    @GetMapping("/maintenance")
    public MaintenanceState maintenance() {
        return broadcast.maintenance();
    }

    @PutMapping("/maintenance")
    public MaintenanceState setMaintenance(
        @RequestHeader(value = "X-Master-Key", required = false) String presentedMasterKey,
        @RequestBody MaintenanceUpdate body
    ) {
        TenantDeletionController.authenticateMasterKey(masterKey, presentedMasterKey);
        return broadcast.updateMaintenance(body, actor());
    }

    @GetMapping("/announcement")
    public AnnouncementState announcement() {
        return broadcast.announcement();
    }

    @PutMapping("/announcement")
    public AnnouncementState setAnnouncement(@RequestBody AnnouncementUpdate body) {
        return broadcast.updateAnnouncement(body, actor());
    }

    private static String actor() {
        return TenantContext.current().map(TenantContext::getSubject).orElse(null);
    }
}
