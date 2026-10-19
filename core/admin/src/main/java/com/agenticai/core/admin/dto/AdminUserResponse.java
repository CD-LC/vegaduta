package com.agenticai.core.admin.dto;

import com.agenticai.core.admin.keycloak.KeycloakUserRepresentation;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

/**
 * {@code inviteLink} is set only on the response to an invite (or a re-issued link) on an install
 * with no mail server: the one-time link the administrator passes on. It is never stored and never
 * appears in a user listing. {@code invitePending}: the person has not chosen a password yet (an
 * open invite or an unused temporary password).
 */
public record AdminUserResponse(
    String id, String username, String email, String firstName, String lastName, boolean enabled,
    List<String> roles, List<String> permissions, boolean invitePending,
    @JsonInclude(JsonInclude.Include.NON_NULL) String inviteLink
) {

    public static AdminUserResponse from(KeycloakUserRepresentation user, List<String> roles, List<String> permissions) {
        return new AdminUserResponse(
            user.id(),
            user.username(),
            user.email(),
            user.firstName(),
            user.lastName(),
            Boolean.TRUE.equals(user.enabled()),
            roles,
            permissions,
            user.requiredActions() != null && user.requiredActions().contains("UPDATE_PASSWORD"),
            null
        );
    }

    public AdminUserResponse withInviteLink(String link) {
        return new AdminUserResponse(
            id, username, email, firstName, lastName, enabled, roles, permissions, invitePending, link);
    }
}
