package com.agenticai.core.tenancy.demo;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Lifetime, per-phone abuse counters for the WhatsApp demo bridge (V353's
 * {@code demo_whatsapp_phone}), keyed by the keyed hash of the sender - never
 * the number itself.
 *
 * <p>Why a separate table: {@link DemoChannelLink} clears {@code external_id}
 * when a link is revoked, so it cannot answer "how many turns has this phone
 * ever spent across all the sandboxes it paired". Without a lifetime bound,
 * one phone could redeem a fresh code for every new sandbox and multiply the
 * free inference the per-sandbox message cap is meant to bound.
 *
 * <p>Both consumers are single atomic upserts guarded by the cap in the
 * {@code WHERE} of the {@code DO UPDATE}, so two concurrent inbound messages
 * cannot both slip under the limit.
 */
@Component
public class DemoWhatsAppPhoneGuard {

    private static final String TURN = "turn_count";
    private static final String PAIR = "pair_count";

    private final JdbcTemplate jdbc;

    public DemoWhatsAppPhoneGuard(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Spends one turn if this phone is still under {@code cap}. False = lifetime cap reached. */
    public boolean tryConsumeTurn(String phoneHash, int cap) {
        return consume(phoneHash, TURN, cap);
    }

    /** Spends one successful pairing if this phone is still under {@code cap}. False = pairing cap reached. */
    public boolean tryConsumePair(String phoneHash, int cap) {
        return consume(phoneHash, PAIR, cap);
    }

    private boolean consume(String phoneHash, String column, int cap) {
        if (phoneHash == null || phoneHash.isBlank() || cap <= 0) {
            return false;
        }
        // 'column' is one of the two constants above, never caller input.
        String initial = TURN.equals(column) ? "1, 0" : "0, 1";
        String sql = "INSERT INTO control_plane.demo_whatsapp_phone AS t "
            + "(phone_hash, turn_count, pair_count, first_seen, last_seen) "
            + "VALUES (?, " + initial + ", ?, ?) "
            + "ON CONFLICT (phone_hash) DO UPDATE SET " + column + " = t." + column + " + 1, last_seen = EXCLUDED.last_seen "
            + "WHERE t." + column + " < ? "
            + "RETURNING " + column;
        Timestamp ts = Timestamp.from(Instant.now());
        List<Integer> rows = jdbc.queryForList(sql, Integer.class, phoneHash, ts, ts, cap);
        return !rows.isEmpty();
    }
}
