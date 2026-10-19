package com.agenticai.core.agents.tools.browseruse;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/**
 * Coverage for {@link BrowserUseSessionManager}'s hard resource caps and off-by-default gate - the
 * two things the build task explicitly calls out ("a hard per-tenant concurrency/session cap and a
 * timeout - headless sessions are resource-heavy, don't leave this unbounded").
 *
 * <p>Deliberately does NOT exercise {@code navigate}/{@code click}/{@code type}/{@code
 * readPageText}/{@code screenshot} here - those need a real Chromium binary launched via {@link
 * com.microsoft.playwright.Playwright}, which this module's test environment does not provision
 * (see the pom.xml's dependency comment: the binary is a deploy-image concern, installed via {@code
 * playwright install chromium}). {@link BrowserSessionExpiry#checkCapacity} is pulled out as a
 * pure static function for exactly this reason - see its own javadoc - so the cap LOGIC has a real
 * unit-test seam independent of whether a browser can actually launch in this environment.
 */
class BrowserUseSessionManagerTest {

    @Test
    void allowsCreatingANewSessionUnderBothCaps() {
        assertThatCode(() -> BrowserSessionExpiry.checkCapacity(0, 0, 2, 10))
                .doesNotThrowAnyException();
        assertThatCode(() -> BrowserSessionExpiry.checkCapacity(1, 5, 2, 10))
                .doesNotThrowAnyException();
    }

    @Test
    void refusesANewSessionAtThePerTenantCap() {
        assertThatThrownBy(() -> BrowserSessionExpiry.checkCapacity(2, 3, 2, 10))
                .isInstanceOf(BrowserUseException.class)
                .hasMessageContaining("2 active browser session");
    }

    @Test
    void refusesANewSessionAtTheGlobalCapEvenWhenThisTenantIsUnderItsOwn() {
        assertThatThrownBy(() -> BrowserSessionExpiry.checkCapacity(0, 10, 2, 10))
                .isInstanceOf(BrowserUseException.class)
                .hasMessageContaining("capacity is fully in use");
    }

    @Test
    void perTenantCapIsCheckedBeforeTheGlobalCap() {
        // Both caps are exceeded at once - the more specific, more actionable
        // message (per-tenant) should win, since "wait for yours to finish"
        // is more useful advice to the caller than "the platform is busy".
        assertThatThrownBy(() -> BrowserSessionExpiry.checkCapacity(5, 10, 2, 10))
                .hasMessageContaining("active browser session");
    }

    @Test
    void isDisabledByDefault() {
        BrowserUseSessionManager manager =
                new BrowserUseSessionManager("false", "", 2, 10, 300, 1800, 20_000, 15_000);
        org.assertj.core.api.Assertions.assertThat(manager.isEnabled()).isFalse();
        manager.shutdown();
    }

    @Test
    void reflectsTheConfiguredEnabledFlag() {
        BrowserUseSessionManager manager =
                new BrowserUseSessionManager("true", "", 2, 10, 300, 1800, 20_000, 15_000);
        org.assertj.core.api.Assertions.assertThat(manager.isEnabled()).isTrue();
        manager.shutdown();
    }
}
