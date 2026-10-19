package com.agenticai.core.common.studioapp;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * The app-scoped end-user token: signed with the APP's own secret, so a token
 * must verify against its own app and refuse every other app - the property
 * that keeps end users inside exactly one app.
 */
class StudioAppTokenServiceTest {

    private final StudioAppTokenService tokens = new StudioAppTokenService();

    private StudioApp app(String secret) {
        StudioApp app = new StudioApp();
        app.setSlug("test-app-slug-001");
        app.setTokenSecret(secret);
        return app;
    }

    private StudioAppUser user(StudioApp app) {
        StudioAppUser u = new StudioAppUser();
        u.setAppId(app.getId());
        u.setEmail("asha@x.io");
        u.setDisplayName("Asha");
        return u;
    }

    @Test
    void issuedTokenVerifiesAgainstItsOwnApp() {
        StudioApp app = app("test-secret-aaaaaaaaaaaaaaaaaaaaaaaa");
        String token = tokens.issue(app, user(app));
        var principal = tokens.verify(app, token);
        assertThat(principal).isPresent();
        assertThat(principal.get().appId()).isEqualTo(app.getId());
        assertThat(principal.get().email()).isEqualTo("asha@x.io");
    }

    @Test
    void refusesTokensSignedByAnotherApp() {
        StudioApp a = app("test-secret-aaaaaaaaaaaaaaaaaaaaaaaa");
        StudioApp b = app("test-secret-bbbbbbbbbbbbbbbbbbbbbbbb");
        String token = tokens.issue(a, user(a));
        assertThat(tokens.verify(b, token)).isEmpty();
    }

    @Test
    void refusesGarbageAndBlankTokens() {
        StudioApp app = app("test-secret-aaaaaaaaaaaaaaaaaaaaaaaa");
        assertThat(tokens.verify(app, "not-a-jwt")).isEmpty();
        assertThat(tokens.verify(app, "")).isEmpty();
        assertThat(tokens.verify(app, null)).isEmpty();
    }
}
