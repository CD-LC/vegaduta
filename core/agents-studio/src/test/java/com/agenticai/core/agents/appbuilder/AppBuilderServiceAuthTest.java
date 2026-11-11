package com.agenticai.core.agents.appbuilder;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.agenticai.core.security.TenantStatusGuard;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

/** The service-token gate and the bounds on the caller-chosen tenant claim. */
class AppBuilderServiceAuthTest {

    @SuppressWarnings("unchecked")
    private static ObjectProvider<TenantStatusGuard> guardOf(TenantStatusGuard guard) {
        ObjectProvider<TenantStatusGuard> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(guard);
        return provider;
    }

    private static AppBuilderServiceAuth auth(String token, String allowed) {
        return new AppBuilderServiceAuth(token, allowed, guardOf(null));
    }

    @Test
    void anUnconfiguredTokenFailsClosedEvenWhenOneIsPresented() {
        assertThatThrownBy(() -> auth(null, "").authenticate("anything"))
                .isInstanceOf(AppBuilderTokenMissingException.class)
                .hasMessageContaining("not configured");
        assertThatThrownBy(() -> auth("  ", "").authenticate("anything"))
                .isInstanceOf(AppBuilderTokenMissingException.class);
    }

    @Test
    void aMissingOrBlankPresentedTokenIsRefused() {
        AppBuilderServiceAuth auth = auth("s3cret", "");
        assertThatThrownBy(() -> auth.authenticate(null))
                .isInstanceOf(AppBuilderTokenMissingException.class)
                .hasMessageContaining("Missing");
        assertThatThrownBy(() -> auth.authenticate(" "))
                .isInstanceOf(AppBuilderTokenMissingException.class);
    }

    @Test
    void aWrongTokenIsInvalidAndTheRightOneIsAccepted() {
        AppBuilderServiceAuth auth = auth("s3cret", "");
        assertThatThrownBy(() -> auth.authenticate("s3cres"))
                .isInstanceOf(AppBuilderTokenInvalidException.class);
        assertThatThrownBy(() -> auth.authenticate("s3cret-and-more"))
                .isInstanceOf(AppBuilderTokenInvalidException.class);
        assertThatCode(() -> auth.authenticate("s3cret")).doesNotThrowAnyException();
    }

    @Test
    void anEmptyAllowListAcceptsAnyNonSystemTenant() {
        AppBuilderServiceAuth auth = auth("t", null);
        assertThatCode(() -> auth.authorizeTenantClaim("acme")).doesNotThrowAnyException();
    }

    @Test
    void aSystemSchemaClaimIsNeverATenant() {
        AppBuilderServiceAuth auth = auth("t", "");
        assertThatThrownBy(() -> auth.authorizeTenantClaim("public"))
                .isInstanceOf(AppBuilderTokenInvalidException.class)
                .hasMessageContaining("reserved schema");
    }

    @Test
    void aConfiguredAllowListConstrainsTheClaimAndIgnoresBlankEntries() {
        AppBuilderServiceAuth auth = auth("t", " acme , ,globex,");
        assertThatCode(() -> auth.authorizeTenantClaim("acme")).doesNotThrowAnyException();
        assertThatCode(() -> auth.authorizeTenantClaim(" globex ")).doesNotThrowAnyException();
        assertThatThrownBy(() -> auth.authorizeTenantClaim("initech"))
                .isInstanceOf(AppBuilderTokenInvalidException.class)
                .hasMessageContaining("allow-list");
    }

    @Test
    void aTenantTheGuardRejectsIsRefusedAndAMissingGuardAllowsAll() {
        TenantStatusGuard guard = mock(TenantStatusGuard.class);
        when(guard.isUsable("live")).thenReturn(true);
        when(guard.isUsable("stopped")).thenReturn(false);
        AppBuilderServiceAuth guarded = new AppBuilderServiceAuth("t", "", guardOf(guard));

        assertThatCode(() -> guarded.requireUsableTenant("live")).doesNotThrowAnyException();
        assertThatThrownBy(() -> guarded.requireUsableTenant("stopped"))
                .isInstanceOf(AppBuilderTokenInvalidException.class)
                .hasMessageContaining("not usable");
        assertThatCode(() -> auth("t", "").requireUsableTenant("stopped"))
                .doesNotThrowAnyException();
    }
}
