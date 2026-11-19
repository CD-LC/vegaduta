package com.agenticai.core.sharing;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Controller-facing facade over {@link ResourceAccessService}'s grant management: returns {@link
 * GrantView} DTOs so the share controllers never handle the {@link ResourceGrant} entity.
 */
@Service
public class ResourceGrantService {

    /** Read model of a {@link ResourceGrant}. */
    public record GrantView(
            UUID id,
            ResourceType resourceType,
            UUID resourceId,
            String granteeUserId,
            PermissionLevel permissionLevel,
            String grantedBy,
            Instant grantedAt) {

        static GrantView of(ResourceGrant g) {
            return new GrantView(
                    g.getId(),
                    g.getResourceType(),
                    g.getResourceId(),
                    g.getGranteeUserId(),
                    g.getPermissionLevel(),
                    g.getGrantedBy(),
                    g.getGrantedAt());
        }
    }

    private final ResourceAccessService accessService;

    public ResourceGrantService(ResourceAccessService accessService) {
        this.accessService = accessService;
    }

    public GrantView grant(
            ResourceType type,
            UUID resourceId,
            String createdBy,
            String granteeUserId,
            PermissionLevel level) {
        return GrantView.of(accessService.grant(type, resourceId, createdBy, granteeUserId, level));
    }

    public List<GrantView> listGrants(ResourceType type, UUID resourceId, String createdBy) {
        return accessService.listGrants(type, resourceId, createdBy).stream()
                .map(GrantView::of)
                .toList();
    }

    public void revoke(ResourceType type, UUID resourceId, String createdBy, UUID grantId) {
        accessService.revoke(type, resourceId, createdBy, grantId);
    }

    public List<GrantView> listSharedWithMe() {
        return accessService.listSharedWithMe().stream().map(GrantView::of).toList();
    }
}
