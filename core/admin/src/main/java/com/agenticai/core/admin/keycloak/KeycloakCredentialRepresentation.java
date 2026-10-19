package com.agenticai.core.admin.keycloak;

/**
 * Keycloak's {@code CredentialRepresentation}, minimal subset: used to set a
 * password when creating a user via {@link KeycloakAdminClient#createUser}.
 *
 * <p>{@code temporary=true} ({@link #temporaryPassword}) forces the new user
 * to change it on first login - nobody, including this platform's own admin
 * flow, should be handing out a permanent password on someone else's behalf.
 *
 * <p>{@code temporary=false} ({@link #permanentPassword}) is for Phase 0
 * Stage 5's self-service signup specifically - the opposite reasoning
 * applies there: the user chose this password themselves in the signup
 * form, so forcing an immediate change would be a broken first-login
 * experience for no security benefit (nobody else ever saw the password to
 * necessitate a rotation).
 */
public record KeycloakCredentialRepresentation(String type, String value, Boolean temporary) {

    public static KeycloakCredentialRepresentation temporaryPassword(String value) {
        return new KeycloakCredentialRepresentation("password", value, true);
    }

    public static KeycloakCredentialRepresentation permanentPassword(String value) {
        return new KeycloakCredentialRepresentation("password", value, false);
    }
}
