package com.agenticai.core.tenancy.dto;

import com.agenticai.core.security.AccountType;
import com.agenticai.core.security.ExperienceMode;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * {@code orgName} becomes both {@link com.agenticai.core.tenancy.Tenant#getName}
 * (must be globally unique) and, sanitized, the tenant's Postgres schema name
 * (see {@link com.agenticai.core.tenancy.SchemaNameSanitizer}).
 * {@code adminPassword} is the admin's own chosen password - stored only in
 * Keycloak (as a permanent, not temporary, credential), never persisted by
 * this application itself.
 *
 * <p>{@code firstName}/{@code lastName} required, not optional - a real bug
 * found verifying this flow end-to-end: this realm's Keycloak User Profile
 * config requires both, and without them a signup succeeded but the new
 * admin could never actually log in until someone manually patched those
 * fields in the Keycloak console.
 *
 * <p>{@code accountType} is now optional and defaults to {@link
 * AccountType#B2C} (see {@code accountTypeOrDefault()}) - the universal-tier
 * product decision means every tenant gets the same 2 users / 5 agents
 * regardless of this value (see {@code TenantLimitsServiceImpl}), so there
 * is no longer a meaningful choice for the signup screen to ask about.
 * {@code experienceMode} IS optional and defaults to {@code SIMPLE} - a
 * brand-new, possibly non-technical signup should land in the
 * everything-automatic experience unless they specifically ask for manual
 * control, not the other way around.
 *
 * <p>{@code newsOptIn} is the signup wizard's OPTIONAL "Send me product
 * updates & news about VegaDuta" checkbox (default-unchecked in the UI) -
 * also optional here, defaulting to {@code false} (see {@code
 * newsOptInOrDefault()}), so an older/direct caller that omits the field
 * entirely still lands opted-out, not opted-in. Persisted onto {@link
 * com.agenticai.core.tenancy.Tenant#isNewsOptIn} (migration V123). The
 * screen's OTHER, REQUIRED consent - agreeing to the Terms of Service and
 * Privacy Policy - is deliberately absent from this record: it is UI-gating
 * only (blocks submission client-side) and has nothing to persist.
 *
 * <p>{@code demoToken} is OPTIONAL and only present when this signup came
 * from the no-registration sandbox (the {@code /try} page appends
 * {@code ?demo=<token>} to its register links and the signup form passes it
 * through). It records the demo &rarr; real conversion on {@code
 * control_plane.demo_session} - see {@code DemoConversionService} - and does
 * NOTHING else: it is never a credential here, never validated as a
 * precondition, and a junk or expired value is silently ignored rather than
 * failing the signup. Normalised via {@code demoTokenOrNull()} (trim,
 * blank &rarr; null).
 */
public record SignupRequest(
    @NotBlank(message = "orgName is required") @Size(max = 255) String orgName,
    @NotBlank(message = "adminEmail is required") @Email
    @Size(max = MAX_EMAIL_LENGTH, message = "adminEmail is too long") String adminEmail,
    @NotBlank(message = "adminPassword is required")
    @Size(min = 12, max = MAX_PASSWORD_LENGTH, message = "adminPassword must be 12-128 characters") String adminPassword,
    @NotBlank(message = "firstName is required") @Size(max = 255) String firstName,
    @NotBlank(message = "lastName is required") @Size(max = 255) String lastName,
    AccountType accountType,
    ExperienceMode experienceMode,
    Boolean newsOptIn,
    @Size(max = 512, message = "demoToken is too long") String demoToken
) {
    /**
     * Upper bound on the password. Without it a multi-megabyte password was
     * accepted and handed to Keycloak's key-stretching hash - a cheap
     * application-level DoS on the public signup endpoint (bug-bounty report,
     * 2026-10). 128 comfortably fits any passphrase or password-manager output.
     */
    public static final int MAX_PASSWORD_LENGTH = 128;

    /** RFC 5321 path limit. */
    public static final int MAX_EMAIL_LENGTH = 254;

    public AccountType accountTypeOrDefault() {
        return accountType != null ? accountType : AccountType.B2C;
    }

    public ExperienceMode experienceModeOrDefault() {
        return experienceMode != null ? experienceMode : ExperienceMode.SIMPLE;
    }

    public boolean newsOptInOrDefault() {
        return newsOptIn != null && newsOptIn;
    }

    /** Trimmed demo token, or null when absent/blank - the shape {@code DemoConversionService} expects. */
    public String demoTokenOrNull() {
        if (demoToken == null || demoToken.isBlank()) {
            return null;
        }
        return demoToken.trim();
    }
}
