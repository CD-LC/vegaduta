package com.agenticai.core.secrets;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Redaction is the return path's half of the no-leak contract. */
class SecretRedactorTest {

    private final SecretRedactor redactor = new SecretRedactor();

    @Test
    void masksTheValueWhereverItAppears() {
        String output = "Called API with key sk_live_abc123 and got 401 for sk_live_abc123";

        String result = redactor.redact(output, Set.of("sk_live_abc123"));

        assertThat(result).doesNotContain("sk_live_abc123");
        assertThat(result).contains(SecretRedactor.MASK);
    }

    @Test
    void masksTheUrlEncodedForm() {
        // A tool that put the secret in a query string re-encodes it, so the
        // raw-value replace alone would miss it.
        String secret = "p@ss word/+=";
        String encoded = java.net.URLEncoder.encode(secret, StandardCharsets.UTF_8);
        String output = "GET /x?token=" + encoded;

        assertThat(redactor.redact(output, Set.of(secret))).doesNotContain(encoded);
    }

    @Test
    void masksTheBase64FormUsedByBasicAuth() {
        String secret = "hunter2hunter2";
        String base64 = Base64.getEncoder().encodeToString(secret.getBytes(StandardCharsets.UTF_8));
        String output = "Authorization: Basic " + base64;

        assertThat(redactor.redact(output, Set.of(secret))).doesNotContain(base64);
    }

    @Test
    void leavesVeryShortSecretsAloneToAvoidCorruptingOutput() {
        // "cat" would otherwise be blanked out of every word containing it,
        // destroying the tool result the agent has to reason about. A secret
        // this short should never have been stored.
        String output = "the catalogue has 12 categories";

        assertThat(redactor.redact(output, Set.of("cat"))).isEqualTo(output);
    }

    @Test
    void isNullAndEmptySafe() {
        assertThat(redactor.redact(null, Set.of("x"))).isNull();
        assertThat(redactor.redact("text", null)).isEqualTo("text");
        assertThat(redactor.redact("text", List.of())).isEqualTo("text");
    }

    @Test
    void containsAnyDetectsAnUnredactedLeak() {
        assertThat(redactor.containsAny("key=sk_live_abc123", Set.of("sk_live_abc123"))).isTrue();
        assertThat(redactor.containsAny("key=[redacted:secret]", Set.of("sk_live_abc123"))).isFalse();
    }

    @Test
    void masksTheJsonEscapedForm() {
        // The regression this was written for: callers redact ALREADY-SERIALIZED
        // JSON (a tool's return value arrives as a JsonNode and is handed on as
        // text). Serialization rewrites " as \" , so the raw value is no longer
        // a substring of the very text being scrubbed and a plain replace misses
        // it entirely. PASSWORD / BASIC_AUTH / CUSTOM secrets are exactly the
        // kinds that contain quotes, backslashes and newlines.
        String secret = "pa$s\"word12";
        String serialized = "{\"sent\":\"pa$s\\\"word12\"}";

        String result = redactor.redact(serialized, Set.of(secret));

        assertThat(result).doesNotContain("word12");
        assertThat(result).contains(SecretRedactor.MASK);
    }

    @Test
    void masksASecretContainingABackslashOrNewlineOnceSerialized() {
        String secret = "line1\nline2\\end";
        String serialized = "{\"v\":\"line1\\nline2\\\\end\"}";

        assertThat(redactor.redact(serialized, Set.of(secret))).doesNotContain("line2");
    }

    @Test
    void containsAnyAlsoSeesTheEncodedForms() {
        // A leak check that only looked for the raw value would pass while a
        // JSON-escaped copy sat in the very string being asserted on.
        String secret = "pa$s\"word12";
        String serialized = "{\"sent\":\"pa$s\\\"word12\"}";

        assertThat(redactor.containsAny(serialized, Set.of(secret))).isTrue();
        assertThat(redactor.containsAny(redactor.redact(serialized, Set.of(secret)), Set.of(secret))).isFalse();
    }
}
