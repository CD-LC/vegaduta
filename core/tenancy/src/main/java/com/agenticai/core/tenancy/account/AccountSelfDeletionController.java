package com.agenticai.core.tenancy.account;

import java.util.Map;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code DELETE /api/account} body {@code {"confirm":"DELETE"}} -> 202
 * {@code {"deleted":true,"tenantDeleted":true|false}}; anything else in
 * {@code confirm} -> 400. App-store account-deletion requirement (Google Play / Apple 5.1.1(v)).
 *
 * <p>SecurityConfig has an explicit {@code authenticated()} matcher for it - any
 * signed-in user may delete their OWN account (identity comes only from
 * TenantContext, never the body). The service additionally requires a recent
 * interactive sign-in (JWT {@code auth_time} within {@code app.account.deletion.max-auth-age},
 * default PT15M, 0 disables) and otherwise answers 401 {@code reauth_required}. Lives beside {@code AccountController}
 * (same base path, which has no DELETE mapping) as its own class because it
 * needs the {@code security}-profile Keycloak admin client.
 */
@RestController
@Profile("security")
public class AccountSelfDeletionController {

    /** Request body. */
    public record DeleteAccountRequest(String confirm) {
    }

    private final AccountSelfDeletionService service;

    public AccountSelfDeletionController(AccountSelfDeletionService service) {
        this.service = service;
    }

    @DeleteMapping("/api/account")
    public ResponseEntity<Map<String, Object>> deleteAccount(@RequestBody(required = false) DeleteAccountRequest request) {
        AccountSelfDeletionService.Result result = service.deleteCurrentAccount(request == null ? null : request.confirm());
        return ResponseEntity.status(HttpStatus.ACCEPTED)
            .body(Map.of("deleted", result.deleted(), "tenantDeleted", result.tenantDeleted()));
    }
}
