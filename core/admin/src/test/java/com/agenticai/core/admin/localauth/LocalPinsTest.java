package com.agenticai.core.admin.localauth;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Base64;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class LocalPinsTest {

    @ParameterizedTest(name = "{0} is accepted")
    @ValueSource(strings = {"482916", "739105", "58204716", "9047261835", "190384726501", "070485", "600213"})
    void sensiblePinsAreAccepted(String pin) {
        assertThat(LocalPins.violation(pin)).isNull();
    }

    @ParameterizedTest(name = "[{index}] {0} -> {1}")
    @CsvSource(
            delimiter = '|',
            textBlock =
                    """
                    ''       | length
                    12345    | length
                    1234567890123 | length
                    12ab56   | digits
                    123 456  | digits
                    ١٢٣٤٥٦   | digits
                    111111   | easy
                    00000000 | easy
                    123456   | easy
                    234567   | easy
                    7890123  | easy
                    987654   | easy
                    109876   | easy
                    121212   | easy
                    123123   | easy
                    696969   | easy
                    112233   | easy
                    998877   | easy
                    445566   | easy
                    123321   | easy
                    159753   | easy
                    147258   | easy
                    """)
    void weakOrMalformedPinsAreRefusedWithAReason(String pin, String why) {
        String violation = LocalPins.violation(pin.equals("''") ? "" : pin);

        assertThat(violation).isNotNull();
        switch (why) {
            case "length" -> assertThat(violation).contains("6 to 12");
            case "digits" -> assertThat(violation).contains("digits");
            default -> assertThat(violation).contains("easy to guess");
        }
    }

    @Test
    void aNullPinIsRefused() {
        assertThat(LocalPins.violation(null)).isNotNull();
    }

    @Test
    void theDeviceSecretIs32RandomBytesInUnpaddedBase64Url() {
        LocalPins pins = new LocalPins(new LocalPasswords());
        String a = pins.newDeviceSecret();
        String b = pins.newDeviceSecret();

        assertThat(a).isNotEqualTo(b).hasSize(43).doesNotContain("=", "+", "/");
        assertThat(Base64.getUrlDecoder().decode(a)).hasSize(32);
    }

    @Test
    void thePinIsOnlyValidTogetherWithTheSecret() {
        LocalPasswords passwords = new LocalPasswords();
        LocalPins pins = new LocalPins(passwords);
        String secret = pins.newDeviceSecret();
        String hash = pins.hash("482916", secret);

        assertThat(hash).doesNotContain("482916").doesNotContain(secret);
        assertThat(pins.matches("482916", secret, hash)).isTrue();
        assertThat(pins.matches("482917", secret, hash)).as("wrong pin").isFalse();
        assertThat(pins.matches("482916", pins.newDeviceSecret(), hash)).as("right pin, other device's secret").isFalse();
        assertThat(pins.matches("482916", null, hash)).isFalse();
        assertThat(pins.matches(null, secret, hash)).isFalse();
        assertThat(pins.matches("482916", secret, null)).as("no such device").isFalse();
        assertThat(passwords.matches("482916", hash)).as("the pin alone is not what was hashed").isFalse();
    }
}
