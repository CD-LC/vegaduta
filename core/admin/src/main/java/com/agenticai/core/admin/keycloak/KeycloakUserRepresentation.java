package com.agenticai.core.admin.keycloak;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;
import java.util.Map;

/**
 * Minimal subset of Keycloak's {@code UserRepresentation} - only the fields this codebase actually
 * reads or writes. {@code @JsonIgnoreProperties(ignoreUnknown = true)} on the way IN (deserializing
 * Keycloak's responses) since Keycloak's real representation has ~30 fields we don't care about;
 * outbound (serializing for a create-user POST) only the non-null fields set here are sent.
 *
 * <p>{@code attributes} added for Phase 0 Stage 5: signup provisions the new tenant-admin user's
 * {@code tenant_id} attribute at creation time (rather than a separate PUT afterward), matching
 * Keycloak's own representation shape (a map of attribute name to a list of string values -
 * Keycloak attributes are always multi-valued at the wire level even for a single-value case like
 * this one).
 *
 * <p>{@code firstName}/{@code lastName} added after a real bug found verifying Stage 5 end-to-end:
 * this class never set them, but this realm's Keycloak User Profile config requires both - every
 * signup or admin-created user got created successfully and then could never actually log in until
 * someone manually patched those two fields in the Keycloak console. Every creation path must
 * supply them now; there is no create-user path left that omits them.
 *
 * <p>{@code requiredActions} added to actually enforce email verification ("ensure mail
 * verification is enabled" - direct feedback): both create paths in {@link KeycloakAdminClient}
 * previously set {@code emailVerified} to {@code true} unconditionally, which skipped verification
 * entirely for every new user regardless of the realm's own {@code verifyEmail} setting. A user
 * only actually gets forced through Keycloak's "Verify Email" screen on first login if they're
 * created with {@code emailVerified=false} AND {@code "VERIFY_EMAIL"} present in {@code
 * requiredActions} - the realm flag alone only affects Keycloak's own native self-registration
 * flow, which this realm has disabled ({@code registrationAllowed: false}) since tenant
 * provisioning always goes through this app's own {@code POST /api/signup}.
 *
 * <p>{@code createdTimestamp} (2026-10-09, platform users list's "created" column) - Keycloak's own
 * epoch-millis creation time, read-only. Omitted from outbound bodies when null so create/update
 * payloads are unchanged.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record KeycloakUserRepresentation(
        String id,
        String username,
        String email,
        String firstName,
        String lastName,
        Boolean enabled,
        Boolean emailVerified,
        List<KeycloakCredentialRepresentation> credentials,
        Map<String, List<String>> attributes,
        List<String> requiredActions,
        @JsonInclude(JsonInclude.Include.NON_NULL) Long createdTimestamp) {

    /**
     * The pre-{@code createdTimestamp} shape every create path and test builds - {@code
     * createdTimestamp} is read-only (Keycloak sets it), so nothing this codebase constructs ever
     * has one.
     */
    public KeycloakUserRepresentation(
            String id,
            String username,
            String email,
            String firstName,
            String lastName,
            Boolean enabled,
            Boolean emailVerified,
            List<KeycloakCredentialRepresentation> credentials,
            Map<String, List<String>> attributes,
            List<String> requiredActions) {
        this(
                id,
                username,
                email,
                firstName,
                lastName,
                enabled,
                emailVerified,
                credentials,
                attributes,
                requiredActions,
                null);
    }
}
