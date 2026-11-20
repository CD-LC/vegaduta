package com.agenticai.core.tenancy.demo;

import org.springframework.stereotype.Component;

/**
 * Lifetime, per-phone abuse counters for the WhatsApp demo bridge (V353's {@code
 * demo_whatsapp_phone}), keyed by the keyed hash of the sender - never the number itself.
 *
 * <p>Why a separate table: {@link DemoChannelLink} clears {@code external_id} when a link is
 * revoked, so it cannot answer "how many turns has this phone ever spent across all the sandboxes
 * it paired". Without a lifetime bound, one phone could redeem a fresh code for every new sandbox
 * and multiply the free inference the per-sandbox message cap is meant to bound.
 *
 * <p>Both consumers delegate to a single atomic upsert guarded by the cap in the {@code WHERE} of
 * the {@code DO UPDATE} (see {@link DemoWhatsAppPhoneJdbcRepository}), so two concurrent inbound
 * messages cannot both slip under the limit.
 */
@Component
public class DemoWhatsAppPhoneGuard {

    private final DemoWhatsAppPhoneJdbcRepository repository;

    public DemoWhatsAppPhoneGuard(DemoWhatsAppPhoneJdbcRepository repository) {
        this.repository = repository;
    }

    /** Spends one turn if this phone is still under {@code cap}. False = lifetime cap reached. */
    public boolean tryConsumeTurn(String phoneHash, int cap) {
        return invalid(phoneHash, cap) ? false : repository.tryConsumeTurn(phoneHash, cap);
    }

    /**
     * Spends one successful pairing if this phone is still under {@code cap}. False = pairing cap
     * reached.
     */
    public boolean tryConsumePair(String phoneHash, int cap) {
        return invalid(phoneHash, cap) ? false : repository.tryConsumePair(phoneHash, cap);
    }

    private static boolean invalid(String phoneHash, int cap) {
        return phoneHash == null || phoneHash.isBlank() || cap <= 0;
    }
}
