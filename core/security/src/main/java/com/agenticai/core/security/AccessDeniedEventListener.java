package com.agenticai.core.security;

/**
 * SPI notified when an authenticated request is denied by the authorization
 * rules (HTTP 403). Implemented in {@code core/admin}
 * ({@code AuditLogAccessDeniedListener}) to write {@code authz.denied} rows
 * into the tenant's audit log - the same dependency-direction reasoning as
 * {@link PermissionAuthoritySource}: the audit store lives outside this
 * module, so this module defines the seam and the admin module fills it.
 *
 * <p>Only authorization DENIALS (403) reach this - authentication failures
 * (401, bad/expired/absent token) go through the authentication entry
 * point, are deliberately not reported here, and would only ever describe
 * anonymous internet noise anyway.
 */
public interface AccessDeniedEventListener {

    /**
     * @param tenantId the denied caller's tenant ({@code null} for a
     *                 tenant-less caller such as {@code platform-admin})
     * @param subject  the caller's Keycloak user id
     * @param method   the HTTP method that was denied
     * @param path     the request URI that was denied
     * @param detail   short machine-readable context (e.g. the required
     *                 authority), may be {@code null}
     */
    void onAccessDenied(String tenantId, String subject, String method, String path, String detail);
}
