package com.agenticai.core.tenancy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * {@link PlatformSettingsService} is the value-parsing layer under more
 * downstream safety controls than anything else in this module -
 * SignupRateLimiter, ModerationThresholdService, PlatformFeatureBudgetService,
 * DemoSessionService/DemoShareService and the abuse auto-suspend switch all
 * read their operational knobs through these typed getters, and every one of
 * those rows is hand-editable in the admin UI. The contract this suite pins
 * down is therefore not "parsing works" but the FALLBACK posture: a missing
 * row and an unparseable row must both land on the CALLER's default - never
 * on 0/false/empty - because the callers chose their defaults to be the safe
 * value for their control. {@code getBoolean} is the sharpest case: if a
 * typo'd value ("ture", "yes", "on") ever started reading as plain {@code
 * Boolean.parseBoolean} semantics (anything-not-"true" == false), an operator
 * who meant to ENABLE a safety flag would have silently disabled it, with no
 * error anywhere.
 */
class PlatformSettingsServiceTest {

    private PlatformSettingRepository repository;
    private PlatformSettingsService service;

    @BeforeEach
    void setUp() {
        repository = mock(PlatformSettingRepository.class);
        service = new PlatformSettingsService(repository);
    }

    /** One stored row - description is irrelevant to every getter, so null. */
    private void storedValue(String key, String value) {
        when(repository.findById(key)).thenReturn(Optional.of(new PlatformSetting(key, value, null)));
    }

    private void missingRow(String key) {
        when(repository.findById(key)).thenReturn(Optional.empty());
    }

    // -- getInt --

    @Test
    void getIntMissingRowFallsBackToTheCallersDefaultNotZero() {
        // A fresh database that predates a setting's seed migration must
        // behave as if the setting were at its shipped default - a 0 here
        // would e.g. set a rate limit of "never allow anything".
        missingRow("signup.max-per-hour");
        assertEquals(7, service.getInt("signup.max-per-hour", 7));
    }

    @Test
    void getIntUnparseableStoredValueFallsBackToTheCallersDefaultNotZero() {
        storedValue("signup.max-per-hour", "banana");
        assertEquals(7, service.getInt("signup.max-per-hour", 7));
    }

    @Test
    void getIntTrimsWhitespaceAroundAStoredNumericValue() {
        // A value pasted into the admin UI with a stray space must still
        // parse - falling back to the default here would silently ignore an
        // edit the operator believes they made.
        storedValue("signup.max-per-hour", "  42  ");
        assertEquals(42, service.getInt("signup.max-per-hour", 7));
    }

    // -- getLong / getDouble: same posture, wider types --

    @Test
    void getLongParsesAValueBeyondIntRangeAndTrimsIt() {
        storedValue("budget.tokens-per-minute", " 9999999999 "); // > Integer.MAX_VALUE on purpose
        assertEquals(9_999_999_999L, service.getLong("budget.tokens-per-minute", 5L));
    }

    @Test
    void getLongMissingAndUnparseableBothFallBackToTheCallersDefault() {
        missingRow("budget.tokens-per-minute");
        assertEquals(5L, service.getLong("budget.tokens-per-minute", 5L));

        storedValue("budget.tokens-per-minute", "unlimited");
        assertEquals(5L, service.getLong("budget.tokens-per-minute", 5L));
    }

    @Test
    void getDoubleParsesAFractionalValueAndTrimsIt() {
        storedValue("moderation.spike-multiplier", " 0.25 ");
        assertEquals(0.25d, service.getDouble("moderation.spike-multiplier", 3.0d));
    }

    @Test
    void getDoubleMissingAndUnparseableBothFallBackToTheCallersDefault() {
        missingRow("moderation.spike-multiplier");
        assertEquals(3.0d, service.getDouble("moderation.spike-multiplier", 3.0d));

        storedValue("moderation.spike-multiplier", "one-quarter");
        assertEquals(3.0d, service.getDouble("moderation.spike-multiplier", 3.0d));
    }

    // -- getBoolean: the safety-flag case --

    @Test
    void getBooleanAcceptsTrueAndFalseCaseInsensitivelyAndTrimmed() {
        storedValue("abuse.auto-suspend", "TRUE");
        assertTrue(service.getBoolean("abuse.auto-suspend", false));

        storedValue("abuse.auto-suspend", "False");
        assertFalse(service.getBoolean("abuse.auto-suspend", true));

        storedValue("abuse.auto-suspend", "  true  ");
        assertTrue(service.getBoolean("abuse.auto-suspend", false));
    }

    @Test
    void getBooleanTypoInAnAdminEditedFlagFallsBackToTheCallersDefaultNeverToFalse() {
        // THE regression this suite exists for: Boolean.parseBoolean("ture")
        // is false, so swapping the strict match for parseBoolean would turn
        // an operator's typo while ENABLING a safety control into a silent
        // disable. The caller's default (true here, because the caller chose
        // fail-safe) must win instead.
        storedValue("abuse.auto-suspend", "ture");
        assertTrue(service.getBoolean("abuse.auto-suspend", true));

        // "yes"/"1" are common admin-UI guesses - same rule, both directions.
        storedValue("abuse.auto-suspend", "yes");
        assertTrue(service.getBoolean("abuse.auto-suspend", true));
        storedValue("abuse.auto-suspend", "1");
        assertFalse(service.getBoolean("abuse.auto-suspend", false));
    }

    @Test
    void getBooleanMissingRowFallsBackToTheCallersDefault() {
        missingRow("demo.enabled");
        assertTrue(service.getBoolean("demo.enabled", true));
        assertFalse(service.getBoolean("demo.enabled", false));
    }

    // -- getString: present-but-blank is PRESENT --

    @Test
    void getStringReturnsABlankStoredValueVerbatimRatherThanFallingBack() {
        // An operator deliberately clearing a value (e.g. emptying an
        // allowlist) is an edit, not an absence - substituting the default
        // would make it impossible to ever store "".
        storedValue("demo.exempt-ips", "");
        assertEquals("", service.getString("demo.exempt-ips", "10.0.0.1"));
    }

    @Test
    void getStringMissingRowFallsBackToTheCallersDefault() {
        missingRow("demo.exempt-ips");
        assertEquals("10.0.0.1", service.getString("demo.exempt-ips", "10.0.0.1"));
    }

    // -- update: generic upsert surface --

    @Test
    void updateUpsertsAnUnknownKeyRatherThanRejectingIt() {
        // This is a generic control surface, not a fixed enum of keys - a
        // key with no row yet (e.g. a knob whose seed migration hasn't
        // shipped) is created on first write.
        missingRow("brand.new.knob");
        when(repository.save(any(PlatformSetting.class))).thenAnswer(invocation -> invocation.getArgument(0));

        service.update("brand.new.knob", "17", "admin@example.com");

        ArgumentCaptor<PlatformSetting> saved = ArgumentCaptor.forClass(PlatformSetting.class);
        verify(repository).save(saved.capture());
        assertEquals("brand.new.knob", saved.getValue().getKey());
        assertEquals("17", saved.getValue().getValue());
        assertEquals("admin@example.com", saved.getValue().getUpdatedBy());
    }

    @Test
    void updateMutatesTheExistingRowInPlaceInsteadOfCreatingADuplicate() {
        PlatformSetting existing = new PlatformSetting("signup.max-per-hour", "5", null);
        when(repository.findById("signup.max-per-hour")).thenReturn(Optional.of(existing));
        when(repository.save(existing)).thenReturn(existing);

        service.update("signup.max-per-hour", "10", "admin@example.com");

        // Same instance saved (key is the @Id - a second entity for the same
        // key would be an insert attempt against the primary key), with the
        // new value and audit attribution applied to it.
        ArgumentCaptor<PlatformSetting> saved = ArgumentCaptor.forClass(PlatformSetting.class);
        verify(repository).save(saved.capture());
        assertSame(existing, saved.getValue());
        assertEquals("10", existing.getValue());
        assertEquals("admin@example.com", existing.getUpdatedBy());
    }
}
