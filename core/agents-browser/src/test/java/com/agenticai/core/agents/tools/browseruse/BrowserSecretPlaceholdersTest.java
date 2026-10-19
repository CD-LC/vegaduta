package com.agenticai.core.agents.tools.browseruse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * {@code {{secret.NAME}}} handling for browser_type_into: parsing, the domain-binding gate (refuses
 * when there is no allow-list), substitution and the redaction that keeps the value out of every
 * result string.
 */
class BrowserSecretPlaceholdersTest {

    private static final UUID AGENT = UUID.fromString("cccccccc-cccc-cccc-cccc-cccccccccccc");
    private static final String VALUE = "S3cr3t!pa$$w0rd";

    private static final BrowserDomainPolicy NO_LISTS = BrowserDomainPolicy.INACTIVE;
    private static final BrowserDomainPolicy ALLOW_LOGIN =
            BrowserDomainPolicy.fromConfig("login.example.com,*.corp.example", "", "", "");

    // ---- parsing
    // ----------------------------------------------------------------------------------------------

    @Test
    void findsPlaceholdersInBothSpellingsAndUpperCasesNames() {
        Set<String> names =
                BrowserSecretPlaceholders.names(
                        "user={{secret.PORTAL_USER}} pw={{ secret : portal_pass }} again {{secret.PORTAL_USER}}");
        assertThat(names).containsExactly("PORTAL_USER", "PORTAL_PASS");
        assertThat(BrowserSecretPlaceholders.containsPlaceholder("hello")).isFalse();
        assertThat(BrowserSecretPlaceholders.containsPlaceholder("{{secret.X}}")).isTrue();
        assertThat(BrowserSecretPlaceholders.names(null)).isEmpty();
    }

    @Test
    void malformedReferencesAreRefusedNotTypedLiterally() {
        assertThatThrownBy(() -> BrowserSecretPlaceholders.requireWellFormed("{{secret.}}"))
                .isInstanceOf(BrowserUseException.class)
                .hasMessageContaining("malformed");
        assertThatThrownBy(() -> BrowserSecretPlaceholders.requireWellFormed("{{secret.bad-name}}"))
                .isInstanceOf(BrowserUseException.class);
        assertThatThrownBy(
                        () ->
                                BrowserSecretPlaceholders.requireWellFormed(
                                        "ok {{secret.A}} but {{secret.1BAD}}"))
                .isInstanceOf(BrowserUseException.class);
        assertThatCode(
                        () ->
                                BrowserSecretPlaceholders.requireWellFormed(
                                        "plain text, {{not.a.secret}} and {{secret.GOOD_1}}"))
                .doesNotThrowAnyException();
        assertThatCode(() -> BrowserSecretPlaceholders.requireWellFormed(null))
                .doesNotThrowAnyException();
    }

    // ---- domain gate
    // --------------------------------------------------------------------------------------------

    @Test
    void refusesWhenThereIsNoAllowListAndNoBinding() {
        assertThatThrownBy(
                        () ->
                                BrowserSecretPlaceholders.authorize(
                                        Set.of("PORTAL_PASS"),
                                        "login.example.com",
                                        Map.of(),
                                        NO_LISTS,
                                        AGENT))
                .isInstanceOf(BrowserUseException.class)
                .hasMessageContaining("PORTAL_PASS")
                .hasMessageContaining("no allowed-domains list");
    }

    @Test
    void aBlockedOnlyPolicyIsNotAnAllowList() {
        BrowserDomainPolicy blockedOnly =
                BrowserDomainPolicy.fromConfig("", "evil.example", "", "");
        assertThatThrownBy(
                        () ->
                                BrowserSecretPlaceholders.authorize(
                                        Set.of("PORTAL_PASS"),
                                        "login.example.com",
                                        Map.of(),
                                        blockedOnly,
                                        AGENT))
                .isInstanceOf(BrowserUseException.class);
    }

    @Test
    void allowsAnUnboundSecretOnlyOnAnAllowListedHost() {
        assertThatCode(
                        () ->
                                BrowserSecretPlaceholders.authorize(
                                        Set.of("PORTAL_PASS"),
                                        "login.example.com",
                                        Map.of(),
                                        ALLOW_LOGIN,
                                        AGENT))
                .doesNotThrowAnyException();
        assertThatCode(
                        () ->
                                BrowserSecretPlaceholders.authorize(
                                        Set.of("PORTAL_PASS"),
                                        "hr.corp.example",
                                        Map.of(),
                                        ALLOW_LOGIN,
                                        AGENT))
                .doesNotThrowAnyException();
        assertThatThrownBy(
                        () ->
                                BrowserSecretPlaceholders.authorize(
                                        Set.of("PORTAL_PASS"),
                                        "evil.example",
                                        Map.of(),
                                        ALLOW_LOGIN,
                                        AGENT))
                .isInstanceOf(BrowserUseException.class)
                .hasMessageContaining("not permitted");
    }

    @Test
    void anAgentLevelAllowListAlsoCounts() {
        BrowserDomainPolicy agentOnly =
                BrowserDomainPolicy.fromConfig("", "", AGENT + "=login.example.com", "");
        assertThatCode(
                        () ->
                                BrowserSecretPlaceholders.authorize(
                                        Set.of("P"),
                                        "login.example.com",
                                        Map.of(),
                                        agentOnly,
                                        AGENT))
                .doesNotThrowAnyException();
        // a different agent has no allow-list at all
        assertThatThrownBy(
                        () ->
                                BrowserSecretPlaceholders.authorize(
                                        Set.of("P"),
                                        "login.example.com",
                                        Map.of(),
                                        agentOnly,
                                        UUID.randomUUID()))
                .isInstanceOf(BrowserUseException.class);
    }

    @Test
    void aBoundSecretWorksOnlyOnItsOwnDomainsEvenWithoutAnAllowList() {
        Map<String, List<String>> bindings =
                BrowserDomainPolicy.parseNamedMap("BANK_PW=bank.example,*.bank.example");
        assertThatCode(
                        () ->
                                BrowserSecretPlaceholders.authorize(
                                        Set.of("BANK_PW"),
                                        "www.bank.example",
                                        bindings,
                                        NO_LISTS,
                                        AGENT))
                .doesNotThrowAnyException();
        assertThatThrownBy(
                        () ->
                                BrowserSecretPlaceholders.authorize(
                                        Set.of("BANK_PW"),
                                        "login.example.com",
                                        bindings,
                                        ALLOW_LOGIN,
                                        AGENT))
                .isInstanceOf(BrowserUseException.class)
                .hasMessageContaining("bound to specific domains");
    }

    @Test
    void aBoundSecretStillHonoursTheBlockedList() {
        Map<String, List<String>> bindings =
                BrowserDomainPolicy.parseNamedMap("BANK_PW=bank.example");
        BrowserDomainPolicy blocked = BrowserDomainPolicy.fromConfig("", "bank.example", "", "");
        assertThatThrownBy(
                        () ->
                                BrowserSecretPlaceholders.authorize(
                                        Set.of("BANK_PW"),
                                        "bank.example",
                                        bindings,
                                        blocked,
                                        AGENT))
                .isInstanceOf(BrowserUseException.class);
    }

    @Test
    void anUnknownCurrentHostIsRefused() {
        assertThatThrownBy(
                        () ->
                                BrowserSecretPlaceholders.authorize(
                                        Set.of("P"), null, Map.of(), ALLOW_LOGIN, AGENT))
                .isInstanceOf(BrowserUseException.class)
                .hasMessageContaining("could not be determined");
        assertThatThrownBy(
                        () ->
                                BrowserSecretPlaceholders.authorize(
                                        Set.of("P"), " ", Map.of(), ALLOW_LOGIN, AGENT))
                .isInstanceOf(BrowserUseException.class);
    }

    @Test
    void noNamesMeansNothingToAuthorize() {
        assertThatCode(
                        () ->
                                BrowserSecretPlaceholders.authorize(
                                        Set.of(), null, Map.of(), NO_LISTS, AGENT))
                .doesNotThrowAnyException();
    }

    @Test
    void theRefusalMessagesNeverContainAValue() {
        assertThatThrownBy(
                        () ->
                                BrowserSecretPlaceholders.authorize(
                                        Set.of("PORTAL_PASS"),
                                        "evil.example",
                                        Map.of(),
                                        ALLOW_LOGIN,
                                        AGENT))
                .hasMessageNotContaining(VALUE);
    }

    // ---- substitution
    // -------------------------------------------------------------------------------------------

    @Test
    void substitutesEveryPlaceholderIncludingDollarSignsInTheValue() {
        String out =
                BrowserSecretPlaceholders.substitute(
                        "u={{secret.USER_NAME}};p={{secret.PORTAL_PASS}}",
                        Map.of("USER_NAME", "alice", "PORTAL_PASS", VALUE));
        assertThat(out).isEqualTo("u=alice;p=" + VALUE);
    }

    @Test
    void substituteLeavesPlainTextAlone() {
        assertThat(BrowserSecretPlaceholders.substitute("just text", Map.of()))
                .isEqualTo("just text");
        assertThat(BrowserSecretPlaceholders.substitute("", Map.of())).isEmpty();
    }

    @Test
    void substituteFailsWhenAValueIsMissingInsteadOfTypingThePlaceholder() {
        assertThatThrownBy(() -> BrowserSecretPlaceholders.substitute("{{secret.NOPE}}", Map.of()))
                .isInstanceOf(BrowserUseException.class)
                .hasMessageContaining("NOPE")
                .hasMessageNotContaining(VALUE);
    }

    // ---- redaction
    // ----------------------------------------------------------------------------------------------

    @Test
    void redactsTheValueAndItsEncodedForms() {
        Set<String> values = new LinkedHashSet<>(List.of(VALUE));
        String b64 =
                java.util.Base64.getEncoder()
                        .encodeToString(VALUE.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        String urlEncoded =
                java.net.URLEncoder.encode(VALUE, java.nio.charset.StandardCharsets.UTF_8);
        String text =
                "Welcome back. token="
                        + VALUE
                        + " basic="
                        + b64
                        + " q="
                        + urlEncoded
                        + " json=\""
                        + VALUE
                        + "\"";
        String redacted = BrowserSecretPlaceholders.redact(text, values);
        assertThat(redacted).doesNotContain(VALUE).doesNotContain(b64).doesNotContain(urlEncoded);
        assertThat(redacted).contains("Welcome back.").contains("[redacted:secret]");
        assertThat(BrowserSecretPlaceholders.leaks(text, values)).isTrue();
        assertThat(BrowserSecretPlaceholders.leaks(redacted, values)).isFalse();
    }

    @Test
    void shortValuesTheSharedRedactorIgnoresAreStillScrubbedByExactMatch() {
        Set<String> values = Set.of("4821");
        String text = "PIN accepted: 4821. Reference 99.";
        String redacted = BrowserSecretPlaceholders.redact(text, values);
        assertThat(redacted)
                .doesNotContain("4821")
                .contains("PIN accepted")
                .contains("Reference 99.");
        assertThat(BrowserSecretPlaceholders.leaks(text, values)).isTrue();
        assertThat(BrowserSecretPlaceholders.leaks(redacted, values)).isFalse();
    }

    @Test
    void valuesTooShortToScrubSafelyAreRefusedForTyping() {
        assertThatThrownBy(
                        () -> BrowserSecretPlaceholders.requireTypeable(Map.of("PORTAL_PIN", "ab")))
                .isInstanceOf(BrowserUseException.class)
                .hasMessageContaining("PORTAL_PIN")
                .hasMessageContaining("too short");
        assertThatCode(
                        () ->
                                BrowserSecretPlaceholders.requireTypeable(
                                        Map.of("PORTAL_PIN", "4821", "PW", VALUE)))
                .doesNotThrowAnyException();
        assertThatCode(() -> BrowserSecretPlaceholders.requireTypeable(null))
                .doesNotThrowAnyException();
    }

    @Test
    void redactionWithNoSecretsIsANoOp() {
        assertThat(BrowserSecretPlaceholders.redact("text", Set.of())).isEqualTo("text");
        assertThat(BrowserSecretPlaceholders.redact(null, Set.of(VALUE))).isNull();
    }

    @Test
    void sessionStateScrubsValuesTypedEarlierFromLaterPageText() {
        BrowserV2State state = new BrowserV2State(4, 8);
        assertThat(state.redact("Hello " + VALUE)).isEqualTo("Hello " + VALUE);
        state.rememberSecretValues(Set.of(VALUE));
        assertThat(state.hasSecretValues()).isTrue();
        assertThat(state.redact("Logged in as " + VALUE + " ok"))
                .doesNotContain(VALUE)
                .contains("Logged in as");
    }
}
