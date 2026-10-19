package com.agenticai.core.rl.report;

import static org.assertj.core.api.Assertions.assertThat;

import com.agenticai.core.rl.domain.Episode;
import com.agenticai.core.rl.web.dto.RlSummaryResponse.RewardPoint;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.TimeZone;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** JAVA-040: reward trend days are UTC days, whatever the JVM's default zone is. */
class RlReportingServiceDayBucketTest {

    @Test
    void bucketsByUtcDayRegardlessOfTheHostZone() {
        TimeZone original = TimeZone.getDefault();
        try {
            // 23:30 UTC is already the next day in Asia/Kolkata (+05:30).
            TimeZone.setDefault(TimeZone.getTimeZone("Asia/Kolkata"));
            Episode lateUtc = episode(Instant.parse("2026-09-29T23:30:00Z"), "1.0");
            Episode nextUtc = episode(Instant.parse("2026-09-30T00:30:00Z"), "0.0");

            List<RewardPoint> trend = RlReportingService.dailyRewardTrend(List.of(lateUtc, nextUtc));

            assertThat(trend).extracting(RewardPoint::date).containsExactly("2026-09-29", "2026-09-30");
        } finally {
            TimeZone.setDefault(original);
        }
    }

    private static Episode episode(Instant startedAt, String reward) {
        Episode e = new Episode(UUID.randomUUID(), "s", "t", startedAt, "{}", null);
        e.setReward(new BigDecimal(reward));
        return e;
    }
}
