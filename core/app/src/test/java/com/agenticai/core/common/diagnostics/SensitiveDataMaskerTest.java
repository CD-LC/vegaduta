package com.agenticai.core.common.diagnostics;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Security-critical: this is the class that guarantees no diagnostic explanation tier, including
 * {@code EXPERT_TECHNICAL}, ever surfaces a real secret value, email, phone number, IP address, or
 * filesystem path. Every assertion below feeds a realistic fixture (fake credentials only - never
 * real ones) and asserts the dangerous substring is gone AND, where useful, that the masked
 * placeholder is present so a silent no-op regex can't pass by accident.
 */
class SensitiveDataMaskerTest {

    private final SensitiveDataMasker masker = new SensitiveDataMasker();

    @Test
    void nullAndEmptyInputPassThroughUnchanged() {
        assertThat(masker.mask(null)).isNull();
        assertThat(masker.mask("")).isEmpty();
    }

    @Test
    void ordinaryTextPassesThroughUnchanged() {
        String text = "The upstream call failed after 3 retries with a generic timeout.";
        assertThat(masker.mask(text)).isEqualTo(text);
    }

    @Test
    void redactsAnAwsAccessKey() {
        String masked = masker.mask("Found in .env: AWS_ACCESS_KEY_ID=AKIAABCDEFGHIJKLMNOP");
        assertThat(masked).doesNotContain("AKIAABCDEFGHIJKLMNOP");
        assertThat(masked).contains("[REDACTED]");
    }

    @Test
    void redactsAGoogleApiKey() {
        String masked = masker.mask("key=AIzaSyD-9tSrke72PouQMnMX-a7eZSW0jkFMBWY");
        assertThat(masked).doesNotContain("AIzaSyD-9tSrke72PouQMnMX-a7eZSW0jkFMBWY");
    }

    @Test
    void redactsAGithubToken() {
        String masked = masker.mask("token: ghp_1234567890abcdefghijklmnopqrstuvwxyz");
        assertThat(masked).doesNotContain("ghp_1234567890abcdefghijklmnopqrstuvwxyz");
    }

    @Test
    void redactsASlackToken() {
        String masked = masker.mask("xoxb-1234567890-abcdefghijklmnop");
        assertThat(masked).doesNotContain("xoxb-1234567890-abcdefghijklmnop");
    }

    @Test
    void redactsAnOpenAiStyleKey() {
        String masked =
                masker.mask("Authorization header used sk-ABCDEFGHIJKLMNOPQRSTUVWXYZ012345");
        assertThat(masked).doesNotContain("sk-ABCDEFGHIJKLMNOPQRSTUVWXYZ012345");
    }

    @Test
    void redactsAPemPrivateKeyBlock() {
        String pem =
                "-----BEGIN RSA PRIVATE KEY-----\n"
                        + "MIIEpAIBAAKCAQEA...fakekeydata...\n"
                        + "-----END RSA PRIVATE KEY-----";
        String masked = masker.mask("Config dump: " + pem);
        assertThat(masked).doesNotContain("MIIEpAIBAAKCAQEA");
        assertThat(masked).contains("[REDACTED]");
    }

    @Test
    void redactsAJwt() {
        String jwt =
                "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxMjM0NTY3ODkwIn0.dozjgNryP4J3jVmNHl0w5N_XgL0n3I9PlFUP0THsR8U";
        String masked = masker.mask("Bearer " + jwt);
        assertThat(masked).doesNotContain(jwt);
    }

    @Test
    void redactsGenericPasswordKeyValuePairButKeepsTheKeyName() {
        String masked = masker.mask("Connection failed: password=SuperSecret123!");
        assertThat(masked).doesNotContain("SuperSecret123!");
        assertThat(masked).contains("password");
        assertThat(masked).contains("[REDACTED]");
    }

    @Test
    void redactsGenericApiKeyKeyValuePairCaseInsensitively() {
        String masked = masker.mask("Header: API_KEY: \"abc123def456\"");
        assertThat(masked).doesNotContain("abc123def456");
    }

    @Test
    void redactsConnectionStringCredentialsButKeepsSchemeAndHost() {
        String masked =
                masker.mask("jdbc:postgresql://dbuser:s3cr3tPass@db.internal.example:5432/agentic");
        assertThat(masked).doesNotContain("dbuser:s3cr3tPass");
        assertThat(masked)
                .contains("jdbc:postgresql://[REDACTED]@db.internal.example:5432/agentic");
    }

    @Test
    void redactsAnEmailAddress() {
        String masked = masker.mask("Notify jane.doe@example.org when this recurs.");
        assertThat(masked).doesNotContain("jane.doe@example.org");
        assertThat(masked).contains("[EMAIL]");
    }

    @Test
    void redactsAPhoneNumber() {
        String masked = masker.mask("Escalation contact: +1-555-123-4567 or 9876543210.");
        assertThat(masked).doesNotContain("555-123-4567");
        assertThat(masked).doesNotContain("9876543210");
        assertThat(masked).contains("[PHONE]");
    }

    @Test
    void doesNotFalselyFlagAPlainDateAsAPhoneNumber() {
        String text = "Last occurred on 2026-08-05 around noon.";
        assertThat(masker.mask(text)).isEqualTo(text);
    }

    @Test
    void redactsAnIpv4Address() {
        String masked = masker.mask("Upstream host 10.20.30.40 refused the connection.");
        assertThat(masked).doesNotContain("10.20.30.40");
        assertThat(masked).contains("[IP]");
    }

    @Test
    void redactsAnIpv6Address() {
        String masked = masker.mask("Bound to 2001:db8::ff00:42:8329 on the private interface.");
        assertThat(masked).doesNotContain("2001:db8::ff00:42:8329");
        assertThat(masked).contains("[IP]");
    }

    @Test
    void redactsACreditCardShapedDigitRun() {
        String masked = masker.mask("Card on file: 4111 1111 1111 1111 was declined.");
        assertThat(masked).doesNotContain("4111 1111 1111 1111");
        assertThat(masked).contains("[REDACTED]");
    }

    @Test
    void collapsesAStackTraceFrameToClassMethodFileLineAndStripsTheLeadingPath() {
        String rawWindows =
                "at com.agenticai.core.chat.ChatService.call(C:\\Users\\jdoe\\r"
                        + "epo\\core\\chat\\src\\main\\java\\com\\agenticai\\core\\chat\\ChatService.java:88)";
        String masked = masker.mask(rawWindows);
        assertThat(masked)
                .isEqualTo("at com.agenticai.core.chat.ChatService.call(ChatService.java:88)");
        assertThat(masked).doesNotContain("C:\\Users\\jdoe");

        String rawUnix =
                "at com.agenticai.core.chat.ChatService.call("
                        + "/home/deploy/core/chat/src/main/java/com/agenticai/core/chat/ChatService.java:88)";
        String maskedUnix = masker.mask(rawUnix);
        assertThat(maskedUnix)
                .isEqualTo("at com.agenticai.core.chat.ChatService.call(ChatService.java:88)");
        assertThat(maskedUnix).doesNotContain("/home/deploy");
    }

    @Test
    void masksAWindowsAbsolutePathThatIsNotInsideAStackFrame() {
        String masked =
                masker.mask("Failed to read config file C:\\Users\\jdoe\\secrets\\config.yml");
        assertThat(masked).doesNotContain("C:\\Users\\jdoe");
        assertThat(masked).contains("[PATH]");
    }

    @Test
    void masksAUnixAbsolutePathThatIsNotInsideAStackFrame() {
        String masked = masker.mask("Failed to read config file /home/deploy/secrets/config.yml");
        assertThat(masked).doesNotContain("/home/deploy");
        assertThat(masked).contains("[PATH]");
    }

    @Test
    void combinesMultipleSensitiveValuesInOnePassAndRedactsAllOfThem() {
        String raw =
                "User jane.doe@example.org hit a failure calling"
                        + " jdbc:postgresql://dbuser:s3cr3t@db.internal.example:5432/agentic from"
                        + " 10.20.30.40, api_key=abcdef123456, stack frame at"
                        + " com.agenticai.core.chat.ChatService.call(/opt/app/ChatService.java:42)";
        String masked = masker.mask(raw);

        assertThat(masked)
                .doesNotContain("jane.doe@example.org")
                .doesNotContain("dbuser:s3cr3t")
                .doesNotContain("10.20.30.40")
                .doesNotContain("abcdef123456")
                .doesNotContain("/opt/app")
                .contains("[EMAIL]")
                .contains("[IP]")
                .contains("[REDACTED]")
                .contains("ChatService.java:42");
    }

    @Test
    void masksAnAddressWhateverTheLengthOfItsLocalPart() {
        for (int length : new int[] {63, 64, 65, 70, 300, 5_000}) {
            assertThat(masker.mask("mail " + "a".repeat(length) + "@example.com please"))
                    .as("local part of %d characters", length)
                    .isEqualTo("mail [EMAIL] please");
        }
    }

    @Test
    void masksAPrivateKeyBlockAndKeepsTheTextAroundIt() {
        String key =
                "-----BEGIN RSA PRIVATE KEY-----\nMIIEowIBAAKCAQEA\n-----END RSA PRIVATE KEY-----";
        assertThat(masker.mask("config: " + key + " done")).isEqualTo("config: [REDACTED] done");
    }

    @Test
    void hostileTextOf50kCharactersIsMaskedInBoundedTime() {
        for (String hostile :
                new String[] {
                    "a".repeat(50_000),
                    "a@".repeat(25_000),
                    "-----BEGIN PRIVATE KEY-----".repeat(2_000),
                    "-----BEGIN PRIVATE KEY-----\n".repeat(2_000) + "-----END CERTIFICATE-----"
                }) {
            org.junit.jupiter.api.Assertions.assertTimeoutPreemptively(
                    java.time.Duration.ofSeconds(5), () -> masker.mask(hostile));
        }
    }
}
