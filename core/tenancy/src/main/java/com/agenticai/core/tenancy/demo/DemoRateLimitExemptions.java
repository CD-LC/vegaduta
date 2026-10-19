package com.agenticai.core.tenancy.demo;

import com.agenticai.core.tenancy.PlatformSettingsService;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Shared read of {@code demo.rate-limit.exempt-ips} - a comma-separated
 * allowlist of IP addresses that bypass every per-IP demo control:
 * {@link DemoSessionRateLimiter}'s creation-rate window AND
 * {@link DemoSessionService}'s standing live-sandbox-per-IP cap.
 *
 * <p>Direct request (2026-07-28): "exclude my ip for sandbox... give a
 * control in platform maintenance to exclude ip for cap rate limit" - the
 * operator's own address (and QA/demo-recording addresses) should never see
 * the same throttling a real anonymous visitor does. One shared helper
 * rather than duplicating the parse in both call sites, so the two controls
 * can never drift out of sync on what "exempt" means.
 *
 * <p>Deliberately a plain exact-match set, not CIDR ranges: the operating
 * need is "my home/office IP" and "a QA runner's IP," a handful of exact
 * addresses, not subnets. Exact match also means a typo fails closed (the
 * typo'd entry just never matches anything) rather than accidentally
 * exempting a wider range than intended.
 */
public final class DemoRateLimitExemptions {

    private static final String KEY = "demo.rate-limit.exempt-ips";

    private DemoRateLimitExemptions() {
    }

    public static boolean isExempt(PlatformSettingsService platformSettings, String clientIp) {
        if (clientIp == null || clientIp.isBlank()) {
            return false;
        }
        String raw = platformSettings.getString(KEY, "");
        if (raw == null || raw.isBlank()) {
            return false;
        }
        return parse(raw).contains(clientIp.trim());
    }

    public static Set<String> parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return Set.of();
        }
        return Arrays.stream(raw.split(","))
            .map(String::trim)
            .filter(s -> !s.isEmpty())
            .collect(Collectors.toUnmodifiableSet());
    }

    /**
     * Loose validation for the admin-facing update path - not a strict IPv4/
     * IPv6 grammar (a home connection's IPv6 can be long and varied), just
     * enough to catch an obviously wrong paste (a URL, a sentence) before it
     * silently becomes a no-op exemption. Allows digits, letters (IPv6 hex),
     * dots, colons, and the CIDR-looking `/` a well-meaning operator might
     * type even though ranges are not actually matched (see class javadoc) -
     * rejecting THAT would be a worse surprise than a harmlessly-ignored
     * suffix.
     */
    public static boolean looksLikeIpList(String raw) {
        if (raw == null || raw.isBlank()) {
            return true;
        }
        return Arrays.stream(raw.split(","))
            .map(String::trim)
            .filter(s -> !s.isEmpty())
            .allMatch(s -> s.matches("[0-9a-fA-F.:/]{2,64}"));
    }

    public static String normalize(String raw) {
        Set<String> parsed = parse(raw);
        return parsed.isEmpty() ? "" : String.join(",", parsed.stream().sorted().toList());
    }
}
