package com.agenticai.core.tenancy.demo;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * The atomic capped upsert behind {@link DemoWhatsAppPhoneGuard} (V353's {@code
 * demo_whatsapp_phone}). The cap sits in the {@code WHERE} of the {@code DO UPDATE}, so two
 * concurrent inbound messages cannot both slip under the limit.
 */
@Repository
public class DemoWhatsAppPhoneJdbcRepository {

    private static final String TURN = "turn_count";
    private static final String PAIR = "pair_count";

    private final JdbcTemplate jdbc;

    public DemoWhatsAppPhoneJdbcRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Spends one turn if the phone is still under {@code cap}; false when the cap is reached. */
    public boolean tryConsumeTurn(String phoneHash, int cap) {
        return consume(phoneHash, TURN, cap);
    }

    /**
     * Spends one pairing if the phone is still under {@code cap}; false when the cap is reached.
     */
    public boolean tryConsumePair(String phoneHash, int cap) {
        return consume(phoneHash, PAIR, cap);
    }

    private boolean consume(String phoneHash, String column, int cap) {
        // 'column' is one of the two constants above, never caller input.
        String initial = TURN.equals(column) ? "1, 0" : "0, 1";
        String sql =
                "INSERT INTO control_plane.demo_whatsapp_phone AS t "
                        + "(phone_hash, turn_count, pair_count, first_seen, last_seen) "
                        + "VALUES (?, "
                        + initial
                        + ", ?, ?) "
                        + "ON CONFLICT (phone_hash) DO UPDATE SET "
                        + column
                        + " = t."
                        + column
                        + " + 1, last_seen = EXCLUDED.last_seen "
                        + "WHERE t."
                        + column
                        + " < ? "
                        + "RETURNING "
                        + column;
        Timestamp ts = Timestamp.from(Instant.now());
        List<Integer> rows = jdbc.queryForList(sql, Integer.class, phoneHash, ts, ts, cap);
        return !rows.isEmpty();
    }
}
