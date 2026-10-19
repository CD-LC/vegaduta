package com.agenticai.core.agents.loadtest;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.agenticai.core.tenancy.PlatformSettingsService;
import org.junit.jupiter.api.Test;

/**
 * Pins down the exact safety contract required of this allowlist (see its
 * own javadoc): empty/missing setting is always false, only an EXACT tenant
 * id present in the setting is true, and a malformed entry never blows up
 * the whole parse (nor crashes for any other tenant).
 */
class LoadTestTenantAllowlistTest {

    private static final String KEY = "platform.load-test.tenant-ids";
    private static final String TENANT_A = "11111111-1111-1111-1111-111111111111";
    private static final String TENANT_B = "22222222-2222-2222-2222-222222222222";
    private static final String TENANT_NOT_LISTED = "99999999-9999-9999-9999-999999999999";

    private PlatformSettingsService settingsWithValue(String value) {
        PlatformSettingsService settings = mock(PlatformSettingsService.class);
        when(settings.getString(eq(KEY), anyString())).thenReturn(value);
        return settings;
    }

    @Test
    void defaultEmptySettingIsAlwaysFalse() {
        LoadTestTenantAllowlist allowlist = new LoadTestTenantAllowlist(settingsWithValue(""));
        assertFalse(allowlist.isLoadTestTenant(TENANT_A));
        assertFalse(allowlist.isLoadTestTenant(TENANT_NOT_LISTED));
    }

    @Test
    void nullSettingValueIsAlsoAlwaysFalse() {
        LoadTestTenantAllowlist allowlist = new LoadTestTenantAllowlist(settingsWithValue(null));
        assertFalse(allowlist.isLoadTestTenant(TENANT_A));
    }

    @Test
    void nullOrBlankTenantIdIsAlwaysFalseRegardlessOfSetting() {
        LoadTestTenantAllowlist allowlist = new LoadTestTenantAllowlist(settingsWithValue(TENANT_A));
        assertFalse(allowlist.isLoadTestTenant(null));
        assertFalse(allowlist.isLoadTestTenant(""));
        assertFalse(allowlist.isLoadTestTenant("   "));
    }

    @Test
    void onlyExactListedTenantIdsMatch() {
        LoadTestTenantAllowlist allowlist =
            new LoadTestTenantAllowlist(settingsWithValue(TENANT_A + "," + TENANT_B));

        assertTrue(allowlist.isLoadTestTenant(TENANT_A));
        assertTrue(allowlist.isLoadTestTenant(TENANT_B));
        assertFalse(allowlist.isLoadTestTenant(TENANT_NOT_LISTED));
    }

    @Test
    void matchIsCaseInsensitiveOnTheUuidAndTolerantOfWhitespace() {
        LoadTestTenantAllowlist allowlist =
            new LoadTestTenantAllowlist(settingsWithValue(" " + TENANT_A.toUpperCase() + " , " + TENANT_B + " "));

        assertTrue(allowlist.isLoadTestTenant(TENANT_A));
        assertTrue(allowlist.isLoadTestTenant("  " + TENANT_B.toUpperCase() + "  "));
    }

    @Test
    void malformedEntriesAreSkippedNotThrownAndDoNotSuppressValidEntries() {
        LoadTestTenantAllowlist allowlist = new LoadTestTenantAllowlist(
            settingsWithValue("not-a-uuid,, " + TENANT_A + ",http://evil.example,")
        );

        assertTrue(allowlist.isLoadTestTenant(TENANT_A));
        assertFalse(allowlist.isLoadTestTenant("not-a-uuid"));
    }

    @Test
    void settingThatIsEntirelyMalformedFailsClosed() {
        LoadTestTenantAllowlist allowlist = new LoadTestTenantAllowlist(settingsWithValue("not-a-uuid,also-not-one"));
        assertFalse(allowlist.isLoadTestTenant(TENANT_A));
        assertFalse(allowlist.isLoadTestTenant("not-a-uuid"));
    }

    @Test
    void aTenantIdThatIsNotAUuidCanNeverMatchEvenIfSomehowPassedIn() {
        // Defensive: TenantContext tenant ids are always UUID strings in
        // practice, but this must never throw for any input shape.
        LoadTestTenantAllowlist allowlist = new LoadTestTenantAllowlist(settingsWithValue(TENANT_A));
        assertFalse(allowlist.isLoadTestTenant("not-a-uuid-at-all"));
    }
}
