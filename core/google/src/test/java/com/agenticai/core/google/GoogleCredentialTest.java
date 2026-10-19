package com.agenticai.core.google;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.agenticai.core.google.dto.GoogleConnectionResponse;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The module's scope-guard logic lives in {@link GoogleCredential#hasScope}:
 * the four Google tool callbacks call it before attempting an API call, so
 * a false positive here means an avoidable 403 from Google and a false
 * negative means a capability silently reported as unavailable. Pure entity
 * behavior - no DB, no mocks.
 */
class GoogleCredentialTest {

    @Test
    void hasScopeMatchesWholeSpaceSeparatedTokensOnly() {
        GoogleCredential credential = new GoogleCredential(
            "user@example.com", "ENC(rt)",
            GoogleScopes.GMAIL_READONLY + " " + GoogleScopes.SHEETS);

        assertTrue(credential.hasScope(GoogleScopes.GMAIL_READONLY));
        assertTrue(credential.hasScope(GoogleScopes.SHEETS));
        // Not granted - and critically, a *prefix* of a granted scope must not
        // match (substring matching would claim gmail.read is granted because
        // gmail.readonly is).
        assertFalse(credential.hasScope(GoogleScopes.GMAIL_SEND));
        assertFalse(credential.hasScope("https://www.googleapis.com/auth/gmail.read"));
        assertFalse(credential.hasScope(""));
    }

    @Test
    void hasScopeOnANullScopeStringIsFalseNotAnNpe() {
        // The protected JPA constructor leaves grantedScopes null - reachable
        // for a half-hydrated entity, and the guard must fail closed.
        GoogleCredential blank = new GoogleCredential();

        assertFalse(blank.hasScope(GoogleScopes.SHEETS));
    }

    @Test
    void reconnectReplacesCredentialMaterialAndReactivates() {
        GoogleCredential credential = new GoogleCredential("old@example.com", "ENC(old)", "oldScope");
        credential.revoke();
        assertEquals(GoogleCredentialStatus.REVOKED, credential.getStatus());

        credential.reconnect("new@example.com", "ENC(new)", "newScope");

        assertEquals("new@example.com", credential.getConnectedEmail());
        assertEquals("ENC(new)", credential.getEncryptedRefreshToken());
        assertEquals("newScope", credential.getGrantedScopes());
        assertEquals(GoogleCredentialStatus.ACTIVE, credential.getStatus());
    }

    @Test
    void connectionResponseSplitsScopesAndNeverExposesTheRefreshToken() {
        GoogleCredential credential = new GoogleCredential(
            "user@example.com", "ENC(rt-secret)", "scopeA scopeB");

        GoogleConnectionResponse response = GoogleConnectionResponse.from(credential);

        assertEquals("user@example.com", response.connectedEmail());
        assertEquals(List.of("scopeA", "scopeB"), response.grantedScopes());
        assertEquals("ACTIVE", response.status());
        // The DTO has no token field at all - this asserts the whole record's
        // string form stays free of the ciphertext, the closest a test can get
        // to "the refresh token never leaves the entity".
        assertFalse(response.toString().contains("rt-secret"));
    }

    @Test
    void requestedScopeSetContainsEveryPerProductScope() {
        // GoogleScopes.REQUESTED is what authorizeUrl sends - dropping a scope
        // from the join would silently un-grant a whole product for every new
        // connection. YOUTUBE_READONLY is deliberately NOT in this list: Google
        // rejects drive.file+youtube.readonly in one request (Error 400), so it
        // is granted via the chained incremental consent instead.
        for (String scope : List.of(
            GoogleScopes.DRIVE_FILE, GoogleScopes.GMAIL_SEND,
            GoogleScopes.CALENDAR_EVENTS, GoogleScopes.SHEETS)) {
            assertTrue(List.of(GoogleScopes.REQUESTED.split(" ")).contains(scope),
                "REQUESTED must contain " + scope);
        }
        assertTrue(GoogleScopes.REQUESTED.contains("openid"), "identity scopes must stay first-class");
    }

    @Test
    void requestedScopeSetCarriesNoGoogleRestrictedScope() {
        // A restricted scope in the base connect means the OAuth client can't be
        // published without a CASA assessment - unpublished, every non-test-user
        // gets Error 403: access_denied (prod, 2026-10-09).
        for (String restricted : List.of(
            GoogleScopes.GMAIL_READONLY, "https://mail.google.com/",
            "https://www.googleapis.com/auth/gmail.modify", "https://www.googleapis.com/auth/drive",
            "https://www.googleapis.com/auth/drive.readonly")) {
            assertFalse(List.of(GoogleScopes.REQUESTED.split(" ")).contains(restricted),
                "REQUESTED must not contain restricted scope " + restricted);
        }
    }
}
