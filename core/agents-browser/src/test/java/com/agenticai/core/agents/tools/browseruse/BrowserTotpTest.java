package com.agenticai.core.agents.tools.browseruse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/**
 * RFC 6238 Appendix B test vectors (8 digits, 30 s step) pin {@link BrowserTotp} for all three
 * HMACs, plus the seed parsing the {@code browser_totp} tool relies on (bare base32 and {@code
 * otpauth://} URIs) and its failure modes.
 */
class BrowserTotpTest {

    private static final byte[] SHA1_SEED =
            "12345678901234567890".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] SHA256_SEED =
            "12345678901234567890123456789012".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] SHA512_SEED =
            "1234567890123456789012345678901234567890123456789012345678901234"
                    .getBytes(StandardCharsets.US_ASCII);

    @Test
    void matchesRfc6238Sha1Vectors() {
        String[][] vectors = {
            {"59", "94287082"}, {"1111111109", "07081804"}, {"1111111111", "14050471"},
            {"1234567890", "89005924"}, {"2000000000", "69279037"}, {"20000000000", "65353130"}
        };
        for (String[] v : vectors) {
            assertThat(BrowserTotp.generate(SHA1_SEED, Long.parseLong(v[0]), 30, 8, "SHA1"))
                    .as("t=" + v[0])
                    .isEqualTo(v[1]);
        }
    }

    @Test
    void matchesRfc6238Sha256Vectors() {
        String[][] vectors = {
            {"59", "46119246"}, {"1111111109", "68084774"}, {"1111111111", "67062674"},
            {"1234567890", "91819424"}, {"2000000000", "90698825"}, {"20000000000", "77737706"}
        };
        for (String[] v : vectors) {
            assertThat(BrowserTotp.generate(SHA256_SEED, Long.parseLong(v[0]), 30, 8, "SHA256"))
                    .as("t=" + v[0])
                    .isEqualTo(v[1]);
        }
    }

    @Test
    void matchesRfc6238Sha512Vectors() {
        String[][] vectors = {
            {"59", "90693936"}, {"1111111109", "25091201"}, {"1111111111", "99943326"},
            {"1234567890", "93441116"}, {"2000000000", "38618901"}, {"20000000000", "47863826"}
        };
        for (String[] v : vectors) {
            assertThat(BrowserTotp.generate(SHA512_SEED, Long.parseLong(v[0]), 30, 8, "SHA512"))
                    .as("t=" + v[0])
                    .isEqualTo(v[1]);
        }
    }

    @Test
    void sixDigitCodeIsTheLowSixDigitsOfTheEightDigitOne() {
        assertThat(BrowserTotp.generate(SHA1_SEED, 59, 30, 6, "SHA1")).isEqualTo("287082");
        // leading zeros are kept
        assertThat(BrowserTotp.generate(SHA1_SEED, 1111111109L, 30, 6, "SHA1")).isEqualTo("081804");
    }

    @Test
    void algorithmNameIsTolerant() {
        assertThat(BrowserTotp.generate(SHA1_SEED, 59, 30, 8, "HmacSHA1")).isEqualTo("94287082");
        assertThat(BrowserTotp.generate(SHA256_SEED, 59, 30, 8, "sha-256")).isEqualTo("46119246");
    }

    @Test
    void decodesRfc4648Base32WithNoise() {
        assertThat(BrowserTotp.decodeBase32("GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ"))
                .isEqualTo(SHA1_SEED);
        assertThat(BrowserTotp.decodeBase32("gezd gnbv-gy3t qojq gezd gnbv gy3t qojq===="))
                .isEqualTo(SHA1_SEED);
    }

    @Test
    void rejectsNonBase32WithoutEchoingTheInput() {
        assertThatThrownBy(() -> BrowserTotp.decodeBase32("NOT*BASE32!!"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageNotContaining("NOT*BASE32");
        assertThatThrownBy(() -> BrowserTotp.decodeBase32("   "))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void parsesABareSeedWithDefaults() {
        BrowserTotp.Config config = BrowserTotp.parseSecret("  GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ ");

        assertThat(config.key()).isEqualTo(SHA1_SEED);
        assertThat(config.algorithm()).isEqualTo("SHA1");
        assertThat(config.digits()).isEqualTo(6);
        assertThat(config.periodSeconds()).isEqualTo(30);
        assertThat(BrowserTotp.codeAt(config, 59).value()).isEqualTo("287082");
    }

    @Test
    void otpauthUriParametersOverrideTheDefaults() {
        BrowserTotp.Config config =
                BrowserTotp.parseSecret(
                        "otpauth://totp/Example:alice@example.com?secret=GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ&issuer=Example"
                                + "&algorithm=SHA256&digits=8&period=60");

        assertThat(config.algorithm()).isEqualTo("SHA256");
        assertThat(config.digits()).isEqualTo(8);
        assertThat(config.periodSeconds()).isEqualTo(60);
    }

    @Test
    void reportsTheSecondsLeftInTheCurrentStep() {
        BrowserTotp.Config config = BrowserTotp.parseSecret("GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ");

        assertThat(BrowserTotp.codeAt(config, 60).validForSeconds()).isEqualTo(30);
        assertThat(BrowserTotp.codeAt(config, 89).validForSeconds()).isEqualTo(1);
        assertThat(BrowserTotp.codeAt(config, 75).validForSeconds()).isEqualTo(15);
    }

    @Test
    void refusesUnusableConfigurations() {
        assertThatThrownBy(
                        () ->
                                BrowserTotp.parseSecret(
                                        "otpauth://hotp/x?secret=GEZDGNBVGY3TQOJQ&counter=1"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> BrowserTotp.parseSecret("otpauth://totp/x?issuer=nope"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(
                        () ->
                                BrowserTotp.parseSecret(
                                        "otpauth://totp/x?secret=GEZDGNBVGY3TQOJQ&algorithm=MD5"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(
                        () ->
                                BrowserTotp.parseSecret(
                                        "otpauth://totp/x?secret=GEZDGNBVGY3TQOJQ&digits=12"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> BrowserTotp.parseSecret(""))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> BrowserTotp.generate(SHA1_SEED, 59, 0, 6, "SHA1"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> BrowserTotp.generate(new byte[0], 59, 30, 6, "SHA1"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void theParsedConfigNeverPrintsItsKey() {
        BrowserTotp.Config config = BrowserTotp.parseSecret("GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ");

        assertThat(config.toString()).doesNotContain("GEZD").doesNotContain("[B@").contains("SHA1");
    }
}
