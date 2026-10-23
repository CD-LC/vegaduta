package com.agenticai.core.platform;

import com.agenticai.core.security.TenantContext;
import com.agenticai.core.security.edition.ConditionalOnEditionFeature;
import com.agenticai.core.security.edition.EditionFeature;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Platform-admin demo-sandbox reclaim - {@code GET /api/platform/demo-sandboxes} and {@code POST
 * /api/platform/demo-sandboxes/reclaim}. Role-gated by the blanket {@code /api/platform/** ->
 * ROLE_platform-admin} rule; the reclaim is additionally gated by the same {@code X-Master-Key}
 * second factor as tenant deletion ({@link TenantDeletionController#authenticateMasterKey}),
 * because it is the same irreversible erasure.
 *
 * <p>Its own controller (not more methods on {@link DemoSandboxController}) because it needs
 * {@code @Profile("security")}: the erasure path behind it depends on the Keycloak admin client,
 * which only exists in that profile, while the demo settings/metrics endpoints do not.
 */
@ConditionalOnEditionFeature(EditionFeature.OPERATOR_CONSOLE)
@RestController
@RequestMapping("/api/platform/demo-sandboxes")
@Profile("security")
public class DemoSandboxReclaimController {

    private final DemoSandboxReclaimService reclaimService;
    private final String masterKey;

    public DemoSandboxReclaimController(
            DemoSandboxReclaimService reclaimService,
            @Value("${app.platform.master-key:}") String masterKey) {
        this.reclaimService = reclaimService;
        this.masterKey = masterKey;
    }

    /** {@code tenantIds} absent/empty = the oldest reclaimable sandboxes, up to 50. */
    public record ReclaimRequest(List<UUID> tenantIds, String confirm) {}

    @GetMapping
    public DemoSandboxReclaimService.SandboxListing list() {
        return reclaimService.list();
    }

    @PostMapping("/reclaim")
    public DemoSandboxReclaimService.ReclaimResult reclaim(
            @RequestHeader(value = "X-Master-Key", required = false) String presentedMasterKey,
            @RequestBody ReclaimRequest request) {
        TenantDeletionController.authenticateMasterKey(masterKey, presentedMasterKey);
        if (request == null) {
            throw new IllegalArgumentException(
                    "A request body with confirm=\"RECLAIM\" is required.");
        }
        String actorId = TenantContext.current().map(TenantContext::getSubject).orElse(null);
        return reclaimService.reclaim(request.tenantIds(), request.confirm(), actorId);
    }
}
