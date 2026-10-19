package com.agenticai.core.tenancy;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Disposable-email domain blocklist for signup (PRICING-MONETIZATION-AUDIT-
 * 2026-07-31.md §7 item 1 / approval item E1) - "the floor defense every cohort
 * peer runs". Consulted by {@code TenantProvisioningService#signup} before any
 * side effect; a hit rejects the signup with a 400 ({@link
 * DisposableEmailException}).
 *
 * <p><b>Sources, merged at construction:</b>
 * <ol>
 *   <li>the bundled resource {@code tenancy/disposable-email-domains.txt}
 *       (~200 well-known disposable providers; lowercase, one per line,
 *       {@code #} comments and blank lines ignored), and</li>
 *   <li>{@code app.signup.disposable-email-block.extra-domains} - a
 *       comma-separated config/env list so an operator can extend the blocklist
 *       per deployment without a redeploy of the bundled file.</li>
 * </ol>
 *
 * <p><b>Matching:</b> case-insensitive, on the part after the last {@code @};
 * matches the exact domain AND any subdomain of a listed domain (many
 * disposable providers hand out {@code random-word.mailinator.com}-style
 * subdomains), by walking the domain's parent chain against the set - O(labels),
 * no per-entry scan.
 *
 * <p><b>Fail-OPEN:</b> if the bundled resource can't be loaded, that's logged
 * as an error and signups proceed unblocked (config extra-domains still apply).
 * A broken abuse-filter resource must degrade to "no filter", never to "no
 * signups" - the opposite posture from {@code PlanLimitsService}'s fail-closed
 * Redis fallback, and for the same underlying reason: fail toward the state
 * that can't silently break the business.
 *
 * <p>Master kill switch: {@code app.signup.disposable-email-block.enabled}
 * (default true) - flip to false to disable without touching the list.
 *
 * <p><b>Allowlist (narrow, exact-match only):</b> {@code
 * app.signup.disposable-email-block.allowlist} - a comma-separated list of exact
 * domains exempted from this check regardless of the blocklist, checked BEFORE
 * the blocklist walk. Empty/unset by default. Exists solely to unblock
 * {@code e2e/helpers/test-tenant.ts}'s {@code signUpFreshTenant()} fixture, which
 * needs a real, Playwright-scrapable inbox (mailinator.com) for Keycloak's email-
 * verification step - the disposable-email blocklist has correctly rejected that
 * domain since 2026-07-31, silently blocking the whole signup-dependent E2E
 * surface for a month (docs/AGENT-COORDINATION.md, 2026-08-30 E2E-triage entry).
 * Deliberately exact-match (not a subdomain walk like the blocklist) and set to
 * {@code mailinator.com} ONLY in {@code deploy/.env} (staging) - never in
 * {@code deploy/.env.production} - so production's anti-abuse protection is
 * completely unaffected by this property existing.
 */
@Component
public class DisposableEmailDomainService {

    static final String RESOURCE_PATH = "tenancy/disposable-email-domains.txt";

    private static final Logger log = LoggerFactory.getLogger(DisposableEmailDomainService.class);

    private final boolean enabled;
    private final Set<String> blockedDomains;
    private final Set<String> allowlistedDomains;

    @Autowired
    public DisposableEmailDomainService(
        @Value("${app.signup.disposable-email-block.enabled:true}") boolean enabled,
        @Value("${app.signup.disposable-email-block.extra-domains:}") String extraDomainsCsv,
        @Value("${app.signup.disposable-email-block.allowlist:}") String allowlistCsv
    ) {
        this(enabled, extraDomainsCsv, allowlistCsv, RESOURCE_PATH);
    }

    /** Package-private: lets tests point at a missing/alternate resource to exercise fail-open. */
    DisposableEmailDomainService(boolean enabled, String extraDomainsCsv, String allowlistCsv, String resourcePath) {
        this.enabled = enabled;
        Set<String> domains = new HashSet<>();
        try (InputStream in = DisposableEmailDomainService.class.getClassLoader().getResourceAsStream(resourcePath)) {
            if (in == null) {
                throw new IOException("Classpath resource not found: " + resourcePath);
            }
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    String domain = line.trim().toLowerCase(Locale.ROOT);
                    if (!domain.isEmpty() && !domain.startsWith("#")) {
                        domains.add(domain);
                    }
                }
            }
        } catch (IOException e) {
            // Fail-OPEN by design (see class javadoc): log loudly, block nothing
            // from the bundled list. extra-domains below still apply.
            log.error(
                "Failed to load the disposable-email domain blocklist ({}) - "
                    + "disposable-email blocking degrades to config extra-domains only (fail-open)",
                resourcePath, e
            );
        }
        if (extraDomainsCsv != null && !extraDomainsCsv.isBlank()) {
            for (String extra : extraDomainsCsv.split(",")) {
                String domain = extra.trim().toLowerCase(Locale.ROOT);
                if (!domain.isEmpty()) {
                    domains.add(domain);
                }
            }
        }
        this.blockedDomains = Set.copyOf(domains);

        Set<String> allowlist = new HashSet<>();
        if (allowlistCsv != null && !allowlistCsv.isBlank()) {
            for (String entry : allowlistCsv.split(",")) {
                String domain = entry.trim().toLowerCase(Locale.ROOT);
                if (!domain.isEmpty()) {
                    allowlist.add(domain);
                }
            }
        }
        this.allowlistedDomains = Set.copyOf(allowlist);
        if (!this.allowlistedDomains.isEmpty()) {
            log.warn(
                "Disposable-email blocklist has an active allowlist exemption for {} domain(s) - "
                    + "should only be non-empty on staging (see app.signup.disposable-email-block.allowlist)",
                this.allowlistedDomains.size()
            );
        }

        if (enabled) {
            log.info("Disposable-email blocklist active: {} domains", this.blockedDomains.size());
        } else {
            log.info("Disposable-email blocklist DISABLED via app.signup.disposable-email-block.enabled=false");
        }
    }

    /**
     * True when {@code email}'s domain is a blocked disposable provider (exact
     * or subdomain match). Always false when the feature is disabled or the
     * email has no parseable domain - a malformed email is Bean Validation's
     * ({@code @Email}) problem, not this filter's.
     */
    public boolean isDisposable(String email) {
        if (!enabled || blockedDomains.isEmpty() || email == null) {
            return false;
        }
        int at = email.lastIndexOf('@');
        if (at < 0 || at == email.length() - 1) {
            return false;
        }
        String candidate = email.substring(at + 1).trim().toLowerCase(Locale.ROOT);
        if (allowlistedDomains.contains(candidate)) {
            return false;
        }
        // Walk the parent-domain chain: a.b.mailinator.com -> b.mailinator.com
        // -> mailinator.com -> com. TLDs aren't in the list, so the walk just
        // terminates without a match for clean domains.
        while (!candidate.isEmpty()) {
            if (blockedDomains.contains(candidate)) {
                return true;
            }
            int dot = candidate.indexOf('.');
            if (dot < 0) {
                return false;
            }
            candidate = candidate.substring(dot + 1);
        }
        return false;
    }
}
