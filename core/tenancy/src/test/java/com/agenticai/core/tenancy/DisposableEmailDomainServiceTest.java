package com.agenticai.core.tenancy;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Disposable-email blocklist at signup (PRICING-MONETIZATION-AUDIT-2026-07-31.md
 * §7 item 1 / E1) - {@link DisposableEmailDomainService}'s matching semantics
 * (exact + subdomain, case-insensitive), the config extension point, the master
 * switch, and - most important - the fail-OPEN posture when the bundled resource
 * can't load. Uses the REAL bundled resource (src/main/resources is on the test
 * classpath), so these tests also guard against the file being accidentally
 * emptied or moved.
 */
class DisposableEmailDomainServiceTest {

    private static DisposableEmailDomainService enabledService() {
        return new DisposableEmailDomainService(true, "", "");
    }

    @Test
    void blocksWellKnownDisposableDomains() {
        DisposableEmailDomainService service = enabledService();
        assertTrue(service.isDisposable("someone@mailinator.com"));
        assertTrue(service.isDisposable("someone@guerrillamail.com"));
        assertTrue(service.isDisposable("someone@10minutemail.com"));
        assertTrue(service.isDisposable("someone@temp-mail.org"));
        assertTrue(service.isDisposable("someone@yopmail.com"));
        assertTrue(service.isDisposable("someone@sharklasers.com"));
        assertTrue(service.isDisposable("someone@dispostable.com"));
        assertTrue(service.isDisposable("someone@getnada.com"));
        assertTrue(service.isDisposable("someone@maildrop.cc"));
        assertTrue(service.isDisposable("someone@trashmail.com"));
    }

    @Test
    void blocksSubdomainsOfListedDomains() {
        DisposableEmailDomainService service = enabledService();
        assertTrue(service.isDisposable("someone@random-inbox.mailinator.com"));
        assertTrue(service.isDisposable("someone@a.b.yopmail.com"));
    }

    @Test
    void matchingIsCaseInsensitive() {
        DisposableEmailDomainService service = enabledService();
        assertTrue(service.isDisposable("Someone@MAILINATOR.COM"));
        assertTrue(service.isDisposable("someone@Temp-Mail.Org"));
    }

    @Test
    void allowsLegitimateProviders() {
        DisposableEmailDomainService service = enabledService();
        assertFalse(service.isDisposable("someone@gmail.com"));
        assertFalse(service.isDisposable("someone@outlook.com"));
        assertFalse(service.isDisposable("someone@example.com"));
        // Suffix must match on label boundaries, not substrings: notmailinator.com
        // IS on the list as its own entry, but a lookalike that merely ENDS with
        // a blocked string ("...zzmailinator.com" as one label) must not match.
        assertFalse(service.isDisposable("someone@zzmailinator.example"));
    }

    @Test
    void extraDomainsFromConfigExtendTheBundledList() {
        DisposableEmailDomainService service =
            new DisposableEmailDomainService(true, "burner.example, Another-One.Test ,", "");
        assertTrue(service.isDisposable("someone@burner.example"));
        assertTrue(service.isDisposable("someone@sub.burner.example"));
        assertTrue(service.isDisposable("someone@another-one.test"));
        assertFalse(service.isDisposable("someone@gmail.com"));
    }

    @Test
    void disabledFlagTurnsBlockingOffEntirely() {
        DisposableEmailDomainService service = new DisposableEmailDomainService(false, "", "");
        assertFalse(service.isDisposable("someone@mailinator.com"));
    }

    @Test
    void failsOpenWhenBundledResourceCannotLoad() {
        DisposableEmailDomainService service =
            new DisposableEmailDomainService(true, "", "", "tenancy/does-not-exist.txt");
        // Bundled list unavailable -> nothing from it is blocked (signups proceed).
        assertFalse(service.isDisposable("someone@mailinator.com"));
    }

    @Test
    void configExtraDomainsStillApplyWhenBundledResourceCannotLoad() {
        DisposableEmailDomainService service =
            new DisposableEmailDomainService(true, "burner.example", "", "tenancy/does-not-exist.txt");
        assertTrue(service.isDisposable("someone@burner.example"));
        assertFalse(service.isDisposable("someone@mailinator.com"));
    }

    @Test
    void allowlistExemptsAnExactDomainFromAnOtherwiseBlockedList() {
        // The E2E-fixture-vs-blocklist collision this exists for: mailinator.com
        // is on the bundled list, but a staging-only allowlist entry exempts it.
        DisposableEmailDomainService service = new DisposableEmailDomainService(true, "", "mailinator.com");
        assertFalse(service.isDisposable("someone@mailinator.com"));
        // Sibling disposable domains NOT on the allowlist stay fully blocked -
        // this is an exact-match exemption, not a blanket pass for "similar" domains.
        assertTrue(service.isDisposable("someone@mailinator.net"));
        assertTrue(service.isDisposable("someone@guerrillamail.com"));
        // Allowlist is exact-match only, deliberately NOT a subdomain walk like
        // the blocklist - a subdomain of the allowlisted domain is still blocked.
        assertTrue(service.isDisposable("someone@random-inbox.mailinator.com"));
    }

    @Test
    void allowlistIsEmptyByDefault() {
        // enabledService() passes an empty allowlist - confirms the default
        // (unset app.signup.disposable-email-block.allowlist) blocks mailinator.com
        // exactly as before this property existed, i.e. production is unaffected.
        DisposableEmailDomainService service = enabledService();
        assertTrue(service.isDisposable("someone@mailinator.com"));
    }

    @Test
    void toleratesNullAndMalformedEmails() {
        DisposableEmailDomainService service = enabledService();
        assertFalse(service.isDisposable(null));
        assertFalse(service.isDisposable("no-at-sign"));
        assertFalse(service.isDisposable("trailing-at@"));
    }
}
