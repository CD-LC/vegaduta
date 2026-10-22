package com.agenticai.core.platform.ops;

import com.agenticai.core.platform.TenantDeletionController;
import com.agenticai.core.platform.ops.PlatformMigrationService.MigrationsResponse;
import com.agenticai.core.platform.ops.PlatformVersionService.VersionsResponse;
import com.agenticai.core.platform.ops.ScheduledJobRegistryService.JobView;
import com.agenticai.core.security.TenantContext;
import com.agenticai.core.security.edition.ConditionalOnEditionFeature;
import com.agenticai.core.security.edition.EditionFeature;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Read-mostly operations views for the platform console: deployed versions,
 * scheduled jobs (with an allowlisted, master-key-gated "run now") and
 * migration status. Role-gated by the blanket {@code /api/platform/** ->
 * ROLE_platform-admin} rule.
 */
@ConditionalOnEditionFeature(EditionFeature.OPERATOR_CONSOLE)
@RestController
@RequestMapping("/api/platform")
public class PlatformOpsController {

    private final PlatformVersionService versions;
    private final ScheduledJobRegistryService jobs;
    private final PlatformMigrationService migrations;
    private final String masterKey;

    public PlatformOpsController(PlatformVersionService versions, ScheduledJobRegistryService jobs,
                                 PlatformMigrationService migrations,
                                 @Value("${app.platform.master-key:}") String masterKey) {
        this.versions = versions;
        this.jobs = jobs;
        this.migrations = migrations;
        this.masterKey = masterKey;
    }

    @GetMapping("/version")
    public VersionsResponse version() {
        return versions.versions();
    }

    /**
     * Scheduled jobs registered on the replica that answers, with that replica's
     * in-memory last-run state (see {@link ScheduledJobRegistryService}).
     */
    @GetMapping("/jobs")
    public List<JobView> jobs() {
        return jobs.list();
    }

    /** Starts an allowlisted job asynchronously; 403 for any other job. */
    @PostMapping("/jobs/{id}/run")
    public Map<String, Boolean> runJob(
        @PathVariable("id") String id,
        @RequestHeader(value = "X-Master-Key", required = false) String presentedMasterKey
    ) {
        TenantDeletionController.authenticateMasterKey(masterKey, presentedMasterKey);
        String actor = TenantContext.current().map(TenantContext::getSubject).orElse(null);
        jobs.runNow(id, actor);
        return Map.of("started", true);
    }

    @GetMapping("/migrations")
    public MigrationsResponse migrations() {
        return migrations.status();
    }
}
