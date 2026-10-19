package com.agenticai.core.admin.permission;

import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserPermissionGrantRepository extends JpaRepository<UserPermissionGrant, UUID> {

    List<UserPermissionGrant> findByTenantIdAndKeycloakUserId(String tenantId, String keycloakUserId);

    List<UserPermissionGrant> findByTenantIdAndPermission(String tenantId, String permission);

    void deleteByTenantIdAndKeycloakUserIdAndPermissionIn(String tenantId, String keycloakUserId, Collection<String> permissions);
}
