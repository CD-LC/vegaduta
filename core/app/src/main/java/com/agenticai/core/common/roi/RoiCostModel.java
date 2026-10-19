package com.agenticai.core.common.roi;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/**
 * Pure cost arithmetic for the ROI view: no Spring, no database, no clock. A price is only ever
 * what a tenant (or an operator on its behalf) entered in {@code llm_price}; when there is none, or
 * the call needs a rate that was left blank, the answer is {@link Cost#unpriced}, never a guess.
 * That is the one deliberate difference from {@code LlmPricingCatalog}, which falls back to a fixed
 * mid-range rate so budget enforcement cannot be bypassed - right for enforcement, wrong for a
 * report that must show only recorded data.
 *
 * <p>Token semantics mirror what {@code LlmCallLogService} records: cache write/read tokens are
 * additional to {@code prompt}, and reasoning tokens are additional to {@code completion} and are
 * billed at the output rate.
 */
public final class RoiCostModel {

    private static final BigDecimal MILLION = BigDecimal.valueOf(1_000_000L);
    static final int SCALE = 6;

    private RoiCostModel() {}

    /** One version of a model's rates. A null rate is an unknown rate. */
    public record Rate(
            UUID id,
            String providerName,
            String modelPrefix,
            BigDecimal inputPerMillion,
            BigDecimal outputPerMillion,
            BigDecimal cacheWritePerMillion,
            BigDecimal cacheReadPerMillion,
            LocalDate effectiveFrom) {}

    /** Token counts to price. Null counts from the log are passed as 0 by the caller. */
    public record Usage(
            long prompt, long completion, long cacheCreation, long cacheRead, long reasoning) {}

    /** {@code usd} is null exactly when {@code priced} is false. */
    public record Cost(boolean priced, BigDecimal usd, String unpricedReason) {
        static Cost of(BigDecimal usd) {
            return new Cost(true, usd.setScale(SCALE, RoundingMode.HALF_UP), null);
        }

        static Cost unpriced(String reason) {
            return new Cost(false, null, reason);
        }
    }

    /**
     * The rate that applies to {@code model} on {@code day}: among versions of the same provider
     * whose prefix matches (case-insensitively) and whose {@code effectiveFrom} is not after the
     * day, the longest prefix wins, then the latest effective date.
     */
    public static Optional<Rate> select(
            List<Rate> versions, String providerName, String model, LocalDate day) {
        if (model == null || providerName == null || day == null) {
            return Optional.empty();
        }
        String m = model.toLowerCase(Locale.ROOT);
        return versions.stream()
                .filter(r -> r.providerName().equalsIgnoreCase(providerName))
                .filter(r -> m.startsWith(r.modelPrefix().toLowerCase(Locale.ROOT)))
                .filter(r -> !r.effectiveFrom().isAfter(day))
                .max(
                        Comparator.comparingInt((Rate r) -> r.modelPrefix().length())
                                .thenComparing(Rate::effectiveFrom));
    }

    public static Cost price(Rate rate, Usage usage) {
        if (usage.prompt()
                        + usage.completion()
                        + usage.cacheCreation()
                        + usage.cacheRead()
                        + usage.reasoning()
                == 0) {
            return Cost.of(BigDecimal.ZERO); // nothing was consumed, so no price is needed
        }
        if (rate == null) {
            return Cost.unpriced("NO_PRICE");
        }
        if (rate.inputPerMillion() == null || rate.outputPerMillion() == null) {
            return Cost.unpriced("INPUT_OR_OUTPUT_RATE_BLANK");
        }
        if (usage.cacheCreation() > 0 && rate.cacheWritePerMillion() == null) {
            return Cost.unpriced("CACHE_WRITE_RATE_BLANK");
        }
        if (usage.cacheRead() > 0 && rate.cacheReadPerMillion() == null) {
            return Cost.unpriced("CACHE_READ_RATE_BLANK");
        }
        BigDecimal total =
                part(usage.prompt(), rate.inputPerMillion())
                        .add(part(usage.completion() + usage.reasoning(), rate.outputPerMillion()));
        if (usage.cacheCreation() > 0) {
            total = total.add(part(usage.cacheCreation(), rate.cacheWritePerMillion()));
        }
        if (usage.cacheRead() > 0) {
            total = total.add(part(usage.cacheRead(), rate.cacheReadPerMillion()));
        }
        return Cost.of(total);
    }

    private static BigDecimal part(long tokens, BigDecimal perMillion) {
        return BigDecimal.valueOf(tokens)
                .multiply(perMillion)
                .divide(MILLION, 12, RoundingMode.HALF_UP);
    }
}
