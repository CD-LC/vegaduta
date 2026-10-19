package com.agenticai.core.admin.localauth;

import static org.assertj.core.api.Assertions.assertThat;

import com.agenticai.core.admin.localauth.PasskeyChallenges.Challenge;
import com.agenticai.core.admin.localauth.PasskeyChallenges.Purpose;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class PasskeyChallengesTest {

    LocalAuthServiceTest.MutableClock clock = new LocalAuthServiceTest.MutableClock();
    PasskeyChallenges challenges = new PasskeyChallenges(clock, 3);

    @Test
    void aChallengeIs32RandomBytesWithALowerCaseHexId() {
        Challenge a = challenges.issue(Purpose.LOGIN, null, "https://x.test", "x.test");
        Challenge b = challenges.issue(Purpose.LOGIN, null, "https://x.test", "x.test");

        assertThat(a.bytes()).hasSize(32).isNotEqualTo(b.bytes());
        assertThat(a.id()).matches("[0-9a-f]{32}").isNotEqualTo(b.id());
        assertThat(a.id()).isEqualTo(a.id().toLowerCase());
    }

    @Test
    void itCanBeTakenExactlyOnce() {
        Challenge c = challenges.issue(Purpose.REGISTER, "acct", "https://x.test", "x.test");

        assertThat(challenges.take(c.id(), Purpose.REGISTER)).contains(c);
        assertThat(challenges.take(c.id(), Purpose.REGISTER)).isEmpty();
    }

    @Test
    void itExpiresAfterTwoMinutes() {
        Challenge c = challenges.issue(Purpose.LOGIN, null, "https://x.test", "x.test");
        clock.advance(Duration.ofSeconds(119));
        Challenge alive = challenges.issue(Purpose.LOGIN, null, "https://x.test", "x.test");
        clock.advance(Duration.ofSeconds(2));

        assertThat(challenges.take(c.id(), Purpose.LOGIN)).as("issued 121 s ago").isEmpty();
        assertThat(challenges.take(alive.id(), Purpose.LOGIN)).as("issued 2 s ago").isPresent();
    }

    @Test
    void aChallengeIsOnlyGoodForThePurposeItWasIssuedFor_andIsSpentByTheMistake() {
        Challenge c = challenges.issue(Purpose.LOGIN, null, "https://x.test", "x.test");

        assertThat(challenges.take(c.id(), Purpose.REGISTER)).isEmpty();
        assertThat(challenges.take(c.id(), Purpose.LOGIN)).as("already spent by the wrong-purpose attempt").isEmpty();
    }

    @Test
    void unknownAndNullIdsGiveNothing() {
        assertThat(challenges.take("nope", Purpose.LOGIN)).isEmpty();
        assertThat(challenges.take(null, Purpose.LOGIN)).isEmpty();
    }

    @Test
    void theStoreIsBoundedAndDropsTheOldestFirst() {
        Challenge first = challenges.issue(Purpose.LOGIN, null, "https://x.test", "x.test");
        Challenge second = challenges.issue(Purpose.LOGIN, null, "https://x.test", "x.test");
        Challenge third = challenges.issue(Purpose.LOGIN, null, "https://x.test", "x.test");
        Challenge fourth = challenges.issue(Purpose.LOGIN, null, "https://x.test", "x.test");

        assertThat(challenges.size()).isEqualTo(3);
        assertThat(challenges.take(first.id(), Purpose.LOGIN)).as("evicted").isEmpty();
        assertThat(challenges.take(second.id(), Purpose.LOGIN)).isPresent();
        assertThat(challenges.take(third.id(), Purpose.LOGIN)).isPresent();
        assertThat(challenges.take(fourth.id(), Purpose.LOGIN)).isPresent();
    }

    @Test
    void floodingNeverGrowsPastTheBound() {
        for (int i = 0; i < 1000; i++) {
            challenges.issue(Purpose.LOGIN, null, "https://x.test", "x.test");
        }
        assertThat(challenges.size()).isEqualTo(3);
    }

    @Test
    void expiredEntriesAreSweptOnTheNextIssue() {
        challenges.issue(Purpose.LOGIN, null, "https://x.test", "x.test");
        challenges.issue(Purpose.LOGIN, null, "https://x.test", "x.test");
        clock.advance(Duration.ofMinutes(3));
        challenges.issue(Purpose.LOGIN, null, "https://x.test", "x.test");

        assertThat(challenges.size()).isEqualTo(1);
    }
}
