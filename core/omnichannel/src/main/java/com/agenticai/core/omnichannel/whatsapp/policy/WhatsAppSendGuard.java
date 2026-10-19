package com.agenticai.core.omnichannel.whatsapp.policy;

import com.agenticai.core.omnichannel.whatsapp.WhatsAppSendBlockedException;
import com.agenticai.core.omnichannel.whatsapp.WhatsAppSendPolicy;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.function.BooleanSupplier;
import com.agenticai.core.tenancy.OffsetLimitRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Outbound controls for WhatsApp template sends: an opt-in record must exist, and a recipient Meta
 * has told us to leave alone (131049 per-user marketing limit, 131050 stopped marketing) is not
 * sent marketing templates. See docs/channels/whatsapp-policy.md.
 */
@Service
public class WhatsAppSendGuard implements WhatsAppSendPolicy {

    /** Meta: "wait at least 24 hours before resending" after 131049. */
    static final Duration MARKETING_LIMIT_BACKOFF = Duration.ofHours(24);

    static final int ERR_MARKETING_LIMIT = 131049;
    static final int ERR_STOPPED_MARKETING = 131050;

    private final WhatsAppOptInRepository optIns;
    private final WhatsAppRecipientBackoffRepository backoffs;
    private final Clock clock;

    @Autowired
    public WhatsAppSendGuard(
            WhatsAppOptInRepository optIns, WhatsAppRecipientBackoffRepository backoffs) {
        this(optIns, backoffs, Clock.systemUTC());
    }

    WhatsAppSendGuard(
            WhatsAppOptInRepository optIns,
            WhatsAppRecipientBackoffRepository backoffs,
            Clock clock) {
        this.optIns = optIns;
        this.backoffs = backoffs;
        this.clock = clock;
    }

    /**
     * @param isMarketing evaluated only when the recipient has an active backoff, so the common
     *     path needs no template lookup
     * @throws WhatsAppSendBlockedException when the send must not happen
     */
    @Override
    @Transactional(readOnly = true)
    public void assertMaySendTemplate(String to, BooleanSupplier isMarketing) {
        String digits = digitsOf(to);
        if (digits == null) {
            throw new WhatsAppSendBlockedException("Not a phone number: " + to);
        }
        boolean opted = optIns.findByPhoneDigits(digits).map(WhatsAppOptIn::isActive).orElse(false);
        if (!opted) {
            throw new WhatsAppSendBlockedException(
                    "No WhatsApp opt-in is recorded for this recipient. Record where they agreed to"
                            + " be contacted (POST /api/channels/whatsapp-compliance/opt-in) before sending a"
                            + " template.");
        }
        backoffs.findById(digits)
                .filter(b -> b.blocksAt(clock.instant()))
                .ifPresent(
                        b -> {
                            if (isMarketing.getAsBoolean()) {
                                throw new WhatsAppSendBlockedException(blockedMessage(b));
                            }
                        });
    }

    /** Records Meta's rejection when it is one we back off on; other codes are ignored. */
    @Override
    @Transactional
    public void recordRejection(String to, int metaCode) {
        String digits = digitsOf(to);
        if (digits == null
                || (metaCode != ERR_MARKETING_LIMIT && metaCode != ERR_STOPPED_MARKETING)) {
            return;
        }
        Instant now = clock.instant();
        WhatsAppRecipientBackoff row =
                backoffs.findById(digits).orElseGet(() -> new WhatsAppRecipientBackoff(digits));
        if (metaCode == ERR_STOPPED_MARKETING) {
            row.blockPermanently(metaCode, now);
        } else {
            row.blockUntil(now.plus(MARKETING_LIMIT_BACKOFF), metaCode, now);
        }
        backoffs.save(row);
    }

    /** What callers outside this package may see of an opt-in row. */
    public record OptInView(String phoneDigits, String source, boolean active) {}

    /** Records (or renews) a recipient's opt-in. Fresh consent also lifts a 131050 stop. */
    @Transactional
    public OptInView recordOptIn(String phone, String source, String evidence, String by) {
        String digits = digitsOf(phone);
        if (digits == null) {
            throw new WhatsAppSendBlockedException("Not a phone number: " + phone);
        }
        Instant now = clock.instant();
        WhatsAppOptIn row =
                optIns.findByPhoneDigits(digits)
                        .map(
                                r -> {
                                    r.record(source, evidence, now, by);
                                    return r;
                                })
                        .orElseGet(() -> new WhatsAppOptIn(digits, source, evidence, now, by));
        backoffs.findById(digits).filter(WhatsAppRecipientBackoff::isPermanent).ifPresent(backoffs::delete);
        WhatsAppOptIn saved = optIns.save(row);
        return new OptInView(saved.getPhoneDigits(), saved.getSource(), saved.isActive());
    }

    /** Opt-in row for the list screen: the same fields as {@link OptInView} plus consent timing. No evidence or recorder. */
    public record OptInListItem(
            String phoneDigits, String source, boolean active, Instant consentedAt, Instant revokedAt) {}

    @Transactional(readOnly = true)
    public List<OptInListItem> listOptIns(boolean activeOnly, long offset, int limit) {
        Pageable page = new OffsetLimitRequest(offset, limit);
        List<WhatsAppOptIn> rows =
                activeOnly
                        ? optIns.findByRevokedAtIsNullOrderByConsentedAtDescIdDesc(page)
                        : optIns.findAllByOrderByConsentedAtDescIdDesc(page);
        return rows.stream()
                .map(
                        r ->
                                new OptInListItem(
                                        r.getPhoneDigits(),
                                        r.getSource(),
                                        r.isActive(),
                                        r.getConsentedAt(),
                                        r.getRevokedAt()))
                .toList();
    }

    @Transactional(readOnly = true)
    public long countOptIns(boolean activeOnly) {
        return activeOnly ? optIns.countByRevokedAtIsNull() : optIns.count();
    }

    @Transactional
    public boolean revokeOptIn(String phone) {
        String digits = digitsOf(phone);
        if (digits == null) {
            return false;
        }
        return optIns.findByPhoneDigits(digits)
                .map(
                        r -> {
                            r.revoke(clock.instant());
                            optIns.save(r);
                            return true;
                        })
                .orElse(false);
    }

    /** Digits only; null when there are none. (Not shared with the contact package: that would be a cycle.) */
    static String digitsOf(String value) {
        if (value == null) {
            return null;
        }
        String digits = value.replaceAll("\\D", "");
        return digits.isEmpty() ? null : digits;
    }

    private static String blockedMessage(WhatsAppRecipientBackoff b) {
        return b.isPermanent()
                ? "This recipient stopped receiving marketing messages from the business (Meta"
                        + " error "
                        + b.getMetaCode()
                        + "). Marketing templates are paused until a fresh opt-in is recorded."
                : "Meta's per-user marketing limit applies to this recipient (error "
                        + b.getMetaCode()
                        + "). Marketing templates are paused until "
                        + b.getBlockedUntil()
                        + ".";
    }
}
