package com.agenticai.core.admin.sso;

import com.agenticai.core.admin.identity.IdentityUserAdminPort;
import com.agenticai.core.admin.keycloak.KeycloakUserRepresentation;
import com.agenticai.core.security.TenantLimits;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * SCIM-lite provisioning endpoint - the "SCIM" half of per-tenant SSO productization (P2 item).
 * {@code /scim/v2/**} is {@code permitAll} in {@code SecurityConfig} because the caller is the
 * tenant's IdP (Okta/Entra provisioning job) presenting a {@code vdscim_*} bearer token minted via
 * {@link TenantSsoController#mintToken}, not a Keycloak JWT. The token's SHA-256 hash is matched
 * against {@code tenant_scim_token}; the row's {@code tenant_schema} then scopes EVERY operation
 * below - the token can only ever touch its own tenant's users.
 *
 * <p><b>"Lite", stated plainly:</b> this is the subset Okta/Entra-style provisioning connectors
 * actually need - create, list, get, deactivate ({@code PATCH active:false} / {@code DELETE}). It
 * is NOT full RFC 7644: no filter expressions, no bulk endpoint, no schema discovery. Unknown
 * operations return a SCIM-shaped 400 rather than pretending.
 *
 * <p>Provisioned users are created through {@link IdentityUserAdminPort#createUserForInvite} - no
 * password is ever set here; the user activates via Keycloak's own required-actions email, so a
 * SCIM token holder can add/disable users but can't mint credentials.
 */
// Off under app.identity.provider=local (a personal install): there the SCIM bearer tokens that gate this
// permitAll surface cannot be minted (TenantSsoController is Keycloak-only), so the endpoints would be a
// public, token-guarded door with no IdP behind it. Every other provider value, and the property unset,
// keeps it, exactly as before: Auth0 still supplies IdentityUserAdminPort for it (see TenantSsoController).
@RestController
@RequestMapping("/scim/v2")
@Profile("security")
@ConditionalOnExpression("!'local'.equals('${app.identity.provider:keycloak}')")
public class ScimProvisioningController {

    private static final String SCIM_CONTENT_TYPE = "application/scim+json";
    private static final String LIST_SCHEMA = "urn:ietf:params:scim:api:messages:2.0:ListResponse";
    private static final String USER_SCHEMA = "urn:ietf:params:scim:schemas:core:2.0:User";
    private static final String ERROR_SCHEMA = "urn:ietf:params:scim:api:messages:2.0:Error";

    private final TenantScimTokenRepository tokenRepository;
    private final IdentityUserAdminPort keycloak;
    private final TenantLimits tenantLimits;

    public ScimProvisioningController(
            TenantScimTokenRepository tokenRepository,
            IdentityUserAdminPort keycloak,
            TenantLimits tenantLimits) {
        this.tokenRepository = tokenRepository;
        this.keycloak = keycloak;
        this.tenantLimits = tenantLimits;
    }

    static String sha256Hex(String token) {
        try {
            return HexFormat.of()
                    .formatHex(
                            MessageDigest.getInstance("SHA-256")
                                    .digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    /** Null = unauthorized. Never throws - a wrong token is a 401, not a 500. */
    private String authorizedTenant(String authorization) {
        if (authorization == null || !authorization.startsWith("Bearer ")) {
            return null;
        }
        String token = authorization.substring(7).trim();
        if (token.isEmpty() || token.length() > 512) {
            return null;
        }
        Optional<TenantScimToken> row =
                tokenRepository.findByTokenHashAndRevokedAtIsNull(sha256Hex(token));
        row.ifPresent(t -> tokenRepository.markUsed(t.getId()));
        return row.map(TenantScimToken::getTenantSchema).orElse(null);
    }

    private static ResponseEntity<Map<String, Object>> scimError(HttpStatus status, String detail) {
        return ResponseEntity.status(status)
                .contentType(org.springframework.http.MediaType.valueOf(SCIM_CONTENT_TYPE))
                .body(
                        Map.of(
                                "schemas", List.of(ERROR_SCHEMA),
                                "status", String.valueOf(status.value()),
                                "detail", detail));
    }

    private static Map<String, Object> toScimUser(KeycloakUserRepresentation u) {
        Map<String, List<String>> attrs = u.attributes() == null ? Map.of() : u.attributes();
        return Map.of(
                "schemas", List.of(USER_SCHEMA),
                "id", String.valueOf(u.id()),
                "userName", u.username() == null ? "" : u.username(),
                "active", Boolean.TRUE.equals(u.enabled()),
                "emails",
                        u.email() == null
                                ? List.of()
                                : List.of(Map.of("value", u.email(), "primary", true)),
                "name",
                        Map.of(
                                "givenName", u.firstName() == null ? "" : u.firstName(),
                                "familyName", u.lastName() == null ? "" : u.lastName()),
                "meta", Map.of("resourceType", "User"));
    }

    @GetMapping("/Users")
    @Transactional
    public ResponseEntity<?> listUsers(
            @RequestHeader(value = "Authorization", required = false) String auth) {
        String tenant = authorizedTenant(auth);
        if (tenant == null) {
            return scimError(HttpStatus.UNAUTHORIZED, "Missing or invalid bearer token");
        }
        List<Map<String, Object>> users =
                keycloak.listUsersByTenant(tenant).stream()
                        .map(ScimProvisioningController::toScimUser)
                        .toList();
        return ResponseEntity.ok()
                .contentType(org.springframework.http.MediaType.valueOf(SCIM_CONTENT_TYPE))
                .body(
                        Map.of(
                                "schemas", List.of(LIST_SCHEMA),
                                "totalResults", users.size(),
                                "startIndex", 1,
                                "itemsPerPage", users.size(),
                                "Resources", users));
    }

    @PostMapping("/Users")
    @Transactional
    public ResponseEntity<?> createUser(
            @RequestHeader(value = "Authorization", required = false) String auth,
            @RequestBody Map<String, Object> body) {
        String tenant = authorizedTenant(auth);
        if (tenant == null) {
            return scimError(HttpStatus.UNAUTHORIZED, "Missing or invalid bearer token");
        }
        // Plan gate - provisioning users via SCIM is a paid-tier entitlement
        // (plan_limits.scim_allowed), re-checked at call time so a downgrade
        // stops new provisioning even for already-minted tokens. Reads and
        // deprovisioning (PATCH active:false, DELETE) stay open so an IdP can
        // still clean up after the entitlement ends.
        if (!tenantLimits.scimAllowed(tenant)) {
            return scimError(
                    HttpStatus.FORBIDDEN,
                    "SCIM provisioning is not included in this tenant's plan");
        }
        String userName = stringValue(body.get("userName"));
        if (userName == null || userName.isBlank()) {
            return scimError(HttpStatus.BAD_REQUEST, "userName is required");
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> name =
                body.get("name") instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
        String given = stringValue(name.get("givenName"));
        String family = stringValue(name.get("familyName"));
        // userName doubles as email when no explicit email is supplied -
        // the SCIM-core shape callers (Okta/Entra) always send an email-shaped
        // userName; the first primary email wins when present.
        String email = primaryEmail(body).orElse(userName);

        if (keycloak.findByEmail(email).isPresent()) {
            return scimError(HttpStatus.CONFLICT, "A user with that email already exists");
        }
        // SEC-053: the same plan seat cap UserAdminController#create enforces
        // (hard block, counted against Keycloak's live user list for this
        // tenant) - SCIM must not be a way around the plan's user limit.
        int currentUserCount = keycloak.listUsersByTenant(tenant).size();
        int maxUsers = tenantLimits.maxUsers(tenant);
        if (currentUserCount >= maxUsers) {
            return scimError(
                    HttpStatus.FORBIDDEN,
                    "This plan allows up to "
                            + maxUsers
                            + " user"
                            + (maxUsers == 1 ? "" : "s")
                            + " - upgrade the plan to provision more users.");
        }
        KeycloakUserRepresentation created =
                keycloak.createUserForInvite(userName, email, given, family, tenant);
        // RFC 7644 says active:false at create = created-but-deactivated.
        if (body.get("active") instanceof Boolean b && !b) {
            keycloak.setUserEnabled(created.id(), false);
        }
        return ResponseEntity.status(HttpStatus.CREATED)
                .contentType(org.springframework.http.MediaType.valueOf(SCIM_CONTENT_TYPE))
                .body(toScimUser(keycloak.getUserById(created.id())));
    }

    @GetMapping("/Users/{id}")
    public ResponseEntity<?> getUser(
            @RequestHeader(value = "Authorization", required = false) String auth,
            @PathVariable String id) {
        String tenant = authorizedTenant(auth);
        if (tenant == null) {
            return scimError(HttpStatus.UNAUTHORIZED, "Missing or invalid bearer token");
        }
        KeycloakUserRepresentation user = findUser(id);
        if (!belongsToTenant(user, tenant)) {
            return scimError(HttpStatus.NOT_FOUND, "User not found");
        }
        return ResponseEntity.ok()
                .contentType(org.springframework.http.MediaType.valueOf(SCIM_CONTENT_TYPE))
                .body(toScimUser(user));
    }

    /**
     * Lite PATCH: only {@code active} replacement is honored - the deprovision/reprovision op real
     * IdPs send. Anything else 400s so a caller learns the limit instead of silently half-applying.
     */
    @PatchMapping("/Users/{id}")
    public ResponseEntity<?> patchUser(
            @RequestHeader(value = "Authorization", required = false) String auth,
            @PathVariable String id,
            @RequestBody Map<String, Object> body) {
        String tenant = authorizedTenant(auth);
        if (tenant == null) {
            return scimError(HttpStatus.UNAUTHORIZED, "Missing or invalid bearer token");
        }
        KeycloakUserRepresentation user = findUser(id);
        if (!belongsToTenant(user, tenant)) {
            return scimError(HttpStatus.NOT_FOUND, "User not found");
        }
        Boolean desired = extractActiveFlag(body);
        if (desired == null) {
            return scimError(
                    HttpStatus.BAD_REQUEST,
                    "Only active:true/false replacement is supported by this SCIM-lite endpoint");
        }
        // Plan gate on REACTIVATION only - deprovisioning (active:false) is
        // cleanup and must keep working after a downgrade.
        if (desired && !tenantLimits.scimAllowed(tenant)) {
            return scimError(
                    HttpStatus.FORBIDDEN,
                    "SCIM provisioning is not included in this tenant's plan");
        }
        keycloak.setUserEnabled(id, desired);
        return ResponseEntity.ok()
                .contentType(org.springframework.http.MediaType.valueOf(SCIM_CONTENT_TYPE))
                .body(toScimUser(keycloak.getUserById(id)));
    }

    /** DELETE = deprovision. We disable rather than delete, so the realm keeps the audit trail. */
    @DeleteMapping("/Users/{id}")
    public ResponseEntity<?> deleteUser(
            @RequestHeader(value = "Authorization", required = false) String auth,
            @PathVariable String id) {
        String tenant = authorizedTenant(auth);
        if (tenant == null) {
            return scimError(HttpStatus.UNAUTHORIZED, "Missing or invalid bearer token");
        }
        KeycloakUserRepresentation user = findUser(id);
        if (!belongsToTenant(user, tenant)) {
            return scimError(HttpStatus.NOT_FOUND, "User not found");
        }
        keycloak.setUserEnabled(id, false);
        return ResponseEntity.noContent().build();
    }

    /** getUserById throws on a Keycloak 404 - for SCIM that IS a "not found", not a fault. */
    private KeycloakUserRepresentation findUser(String id) {
        try {
            return keycloak.getUserById(id);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static boolean belongsToTenant(KeycloakUserRepresentation user, String tenant) {
        if (user == null || user.attributes() == null) {
            return false;
        }
        return user.attributes().getOrDefault("tenant_id", List.of()).contains(tenant);
    }

    private static Boolean extractActiveFlag(Map<String, Object> body) {
        if (body.get("active") instanceof Boolean b) {
            return b;
        }
        if (body.get("Operations") instanceof List<?> ops) {
            for (Object op : ops) {
                if (op instanceof Map<?, ?> m
                        && "replace".equalsIgnoreCase(String.valueOf(m.get("op")))) {
                    Object value = m.get("value");
                    if (value instanceof Map<?, ?> vm && vm.get("active") instanceof Boolean b) {
                        return b;
                    }
                    if ("active".equals(String.valueOf(m.get("path")))
                            && value instanceof Boolean b) {
                        return b;
                    }
                }
            }
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private static Optional<String> primaryEmail(Map<String, Object> body) {
        if (!(body.get("emails") instanceof List<?> emails)) {
            return Optional.empty();
        }
        return emails.stream()
                .filter(Map.class::isInstance)
                .map(e -> (Map<String, Object>) e)
                .filter(e -> Boolean.TRUE.equals(e.get("primary")) || emails.size() == 1)
                .map(e -> stringValue(e.get("value")))
                .filter(v -> v != null && !v.isBlank())
                .findFirst();
    }

    private static String stringValue(Object v) {
        return v == null ? null : String.valueOf(v);
    }
}
