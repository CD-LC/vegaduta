package com.agenticai.core.admin;

/**
 * Thrown by {@link UserAdminController} both when a target user id genuinely
 * doesn't exist in Keycloak <em>and</em> when it exists but belongs to a
 * different tenant than the caller - deliberately the same exception/message
 * either way. Distinguishing "not found" from "found, but not yours" in the
 * response would let one tenant-admin enumerate which user ids exist in
 * other tenants; a flat 404 reveals nothing either way.
 */
public class AdminUserNotFoundException extends RuntimeException {

    public AdminUserNotFoundException(String userId) {
        super("No user found with id " + userId);
    }
}
