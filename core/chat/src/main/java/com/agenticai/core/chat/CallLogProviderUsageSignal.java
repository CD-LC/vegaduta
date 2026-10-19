package com.agenticai.core.chat;

import com.agenticai.core.providers.ProviderHealth;
import com.agenticai.core.providers.ProviderName;
import com.agenticai.core.providers.ProviderUsageSignal;
import com.agenticai.core.security.TenantContext;
import com.agenticai.core.security.TtlCache;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * {@link ProviderUsageSignal} read from the tenant's own {@code llm_call_log}:
 * for each provider, its newest successful call (and that call's model) and
 * its newest failed call inside the last {@link #WINDOW}.
 *
 * <p>This is what lets "pick any usable provider" (the eval judge, Ask-AI, the
 * generators) and "what should a new agent default to" follow the key that is
 * actually answering. Prod 2026-10-05: a workspace's oldest ACTIVE credential
 * was being refused by its provider on every call while a second key worked;
 * both defaults kept choosing the refused one because nothing looked at the
 * call history.
 *
 * <p>Results are held for {@link #TTL} per tenant: the resolver is consulted
 * once per judge / utility call, and the answer only needs to follow reality
 * within seconds, not per call.
 */
@Component
public class CallLogProviderUsageSignal implements ProviderUsageSignal {

    /** How far back a call still says something about a credential. */
    static final Duration WINDOW = Duration.ofDays(14);
    static final Duration TTL = Duration.ofSeconds(30);

    private static final Pattern AUTH_STATUS = Pattern.compile("(?<![0-9])(401|403)(?![0-9])");

    private final LlmCallLogRepository repository;
    private final TtlCache<Map<ProviderName, Usage>> cache =
        new TtlCache<>("provider-usage-signal", TTL, 2_000);

    public CallLogProviderUsageSignal(LlmCallLogRepository repository) {
        this.repository = repository;
    }

    @Override
    public Map<ProviderName, Usage> recentUsage(Collection<ProviderName> providers) {
        if (providers == null || providers.isEmpty()) {
            return Map.of();
        }
        String tenantId = TenantContext.current().map(TenantContext::getTenantId).orElse(null);
        if (tenantId == null) {
            // No tenant on this thread means no schema to read; never cache that.
            return load(providers);
        }
        String key = TtlCache.key(tenantId, providers.stream().map(Enum::name).sorted().reduce("", (a, b) -> a + "," + b));
        return cache.get(key, () -> load(providers));
    }

    private Map<ProviderName, Usage> load(Collection<ProviderName> providers) {
        Instant since = Instant.now().minus(WINDOW);
        Map<ProviderName, Usage> usage = new EnumMap<>(ProviderName.class);
        for (ProviderName provider : providers) {
            Optional<LlmCallLog> success =
                repository.findTopByProviderNameAndSuccessAndStartedAtAfterOrderByStartedAtDesc(provider, true, since);
            Optional<LlmCallLog> failure =
                repository.findTopByProviderNameAndSuccessAndStartedAtAfterOrderByStartedAtDesc(provider, false, since);
            if (success.isEmpty() && failure.isEmpty()) {
                continue;
            }
            usage.put(provider, new Usage(
                success.map(LlmCallLog::getStartedAt).orElse(null),
                success.map(LlmCallLog::getModel).orElse(null),
                failure.map(LlmCallLog::getStartedAt).orElse(null),
                failure.map(f -> looksLikeCredentialRejection(
                    f.getErrorMessage(), f.getExceptionType() + " " + causedByLines(f.getStackTrace()))).orElse(false)));
        }
        return usage;
    }

    private final TtlCache<Map<ProviderName, ProviderHealth>> healthCache =
        new TtlCache<>("provider-health-signal", TTL, 2_000);

    @Override
    public Map<ProviderName, ProviderHealth> health(Collection<ProviderName> providers, java.time.Duration cooldown) {
        if (providers == null || providers.isEmpty()) {
            return Map.of();
        }
        String tenantId = TenantContext.current().map(TenantContext::getTenantId).orElse(null);
        if (tenantId == null) {
            return loadHealth(providers, cooldown);
        }
        String key = TtlCache.key(tenantId, providers.stream().map(Enum::name).sorted().reduce("", (a, b) -> a + "," + b)
            + "|" + cooldown);
        return healthCache.get(key, () -> loadHealth(providers, cooldown));
    }

    private Map<ProviderName, ProviderHealth> loadHealth(Collection<ProviderName> providers, java.time.Duration cooldown) {
        Instant since = Instant.now().minus(WINDOW);
        Map<ProviderName, Usage> usage = load(providers);
        Map<ProviderName, ProviderHealth> out = new EnumMap<>(ProviderName.class);
        for (ProviderName provider : providers) {
            Usage u = usage.get(provider);
            if (u == null) {
                out.put(provider, ProviderHealth.unknownFor((int) WINDOW.toDays()));
                continue;
            }
            int failures = (int) Math.min(Integer.MAX_VALUE,
                repository.countByProviderNameAndSuccessAndStartedAtAfter(provider, false, since));
            int successes = (int) Math.min(Integer.MAX_VALUE,
                repository.countByProviderNameAndSuccessAndStartedAtAfter(provider, true, since));
            String lastError = repository
                .findTopByProviderNameAndSuccessAndStartedAtAfterOrderByStartedAtDesc(provider, false, since)
                .map(f -> summarizeError(f.getErrorMessage(), f.getExceptionType()))
                .orElse(null);
            boolean rejected = u.credentialSuspect();
            out.put(provider, new ProviderHealth(
                rejected ? ProviderHealth.REJECTED : ProviderHealth.OK,
                (int) WINDOW.toDays(), failures, successes, u.lastSuccessAt(), u.lastFailureAt(), lastError,
                rejected && u.lastFailureAt() != null && cooldown != null ? u.lastFailureAt().plus(cooldown) : null));
        }
        return out;
    }

    private static final int MAX_ERROR_CHARS = 160;
    private static final Pattern SECRET_LIKE = Pattern.compile("[A-Za-z0-9_\\-]{20,}");

    /**
     * A short, secret-free line for the providers page: the first line of the logged message with anything key-
     * or token-shaped (20+ url-safe characters) masked, capped. Raw provider messages can echo part of a key.
     */
    static String summarizeError(String errorMessage, String exceptionType) {
        String raw = errorMessage != null && !errorMessage.isBlank() ? errorMessage : exceptionType;
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String firstLine = raw.strip().split("\\R", 2)[0];
        String masked = SECRET_LIKE.matcher(firstLine).replaceAll("***");
        return masked.length() > MAX_ERROR_CHARS ? masked.substring(0, MAX_ERROR_CHARS) + "..." : masked;
    }

    /**
     * The {@code Caused by:} lines of a stored stack trace. Some SDKs hide the
     * real status behind a generic top-level message (Google GenAI surfaces a
     * 401/403 as "Failed to generate content", with the status only on the
     * cause), so the top-level message alone would miss a refused key.
     */
    static String causedByLines(String stackTrace) {
        if (stackTrace == null || stackTrace.isBlank()) {
            return "";
        }
        StringBuilder causes = new StringBuilder();
        for (String line : stackTrace.split("\\R")) {
            if (line.startsWith("Caused by:")) {
                causes.append(line).append(' ');
            }
        }
        return causes.toString();
    }

    /**
     * True when a logged failure reads as the provider refusing the
     * credential itself - the same signals {@link ProviderErrorTranslator}
     * turns into "The AI provider rejected our credentials": an HTTP 401/403,
     * or the provider's own unauthorized / invalid-key wording. A rate limit,
     * timeout, bad request or retired model is NOT this: those say nothing
     * about whether the key works.
     */
    static boolean looksLikeCredentialRejection(String errorMessage, String exceptionType) {
        String text = ((errorMessage == null ? "" : errorMessage) + " " + (exceptionType == null ? "" : exceptionType))
            .toLowerCase(Locale.ROOT);
        if (text.isBlank()) {
            return false;
        }
        return AUTH_STATUS.matcher(text).find()
            || text.contains("unauthorized")
            || text.contains("unauthenticated")
            || text.contains("permission_denied")
            || text.contains("permission denied")
            || text.contains("invalid api key")
            || text.contains("invalid_api_key")
            || text.contains("api key not valid")
            || text.contains("invalid x-api-key")
            || text.contains("invalid_grant")
            || text.contains("credentialexception")
            || text.contains("access token")
            || text.contains("authentication");
    }
}
