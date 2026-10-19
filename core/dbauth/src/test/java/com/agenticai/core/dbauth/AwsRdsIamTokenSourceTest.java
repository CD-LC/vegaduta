package com.agenticai.core.dbauth;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Runs the real AWS SDK {@code RdsUtilities} signer offline with throwaway credentials (signing is
 * local, no request is sent). This proves our wiring produces a well-formed token; it does not
 * prove RDS accepts it.
 */
class AwsRdsIamTokenSourceTest {

    @BeforeEach
    void credentials() {
        System.setProperty("aws.accessKeyId", "unit-test-access-key");
        System.setProperty("aws.secretAccessKey", "unit-test-secret");
    }

    @AfterEach
    void clear() {
        System.clearProperty("aws.accessKeyId");
        System.clearProperty("aws.secretAccessKey");
    }

    @Test
    void signsAConnectTokenForTheJdbcHostPortAndUser() {
        DbTokenSource source =
                AwsRdsIamTokenSource.create(
                        "jdbc:postgresql://mydb.abc123.eu-west-1.rds.amazonaws.com:5432/app?sslmode=require",
                        "agentic",
                        "eu-west-1");
        DbToken token = source.fetch();

        assertThat(token.value())
                .startsWith("mydb.abc123.eu-west-1.rds.amazonaws.com:5432/?")
                .contains("Action=connect")
                .contains("DBUser=agentic")
                // The lifetime AWS enforces is the signed X-Amz-Expires, not our bookkeeping
                // below. The SDK floors it to whole seconds from the signing instant, so a
                // call that crosses a second boundary reports one second less.
                .containsPattern(
                        "X-Amz-Expires=("
                                + (AwsRdsIamTokenSource.TOKEN_LIFETIME.toSeconds() - 1)
                                + "|"
                                + AwsRdsIamTokenSource.TOKEN_LIFETIME.toSeconds()
                                + ")")
                .contains("X-Amz-Signature=")
                .contains("eu-west-1%2Frds-db%2Faws4_request");
        assertThat(token.expiresAt())
                .isBetween(
                        Instant.now().plus(Duration.ofMinutes(14)),
                        Instant.now().plus(Duration.ofMinutes(16)));
    }
}
