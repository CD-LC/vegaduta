package com.agenticai.core.agents.tools.browseruse;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The navigation allow/deny matcher: exact, {@code *.suffix}, blocked-wins, per-agent narrowing,
 * redirect chains (each hop is just another document check), IDN/punycode, and the URL tricks that
 * fool naive "does the string contain the domain" checks (userinfo, suffix lookalikes, backslash,
 * percent-encoded dot).
 */
class BrowserDomainPolicyTest {

    private static final UUID AGENT_A = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private static final UUID AGENT_B = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");

    private static BrowserDomainPolicy allow(String list) {
        return BrowserDomainPolicy.fromConfig(list, "", "", "");
    }

    @Test
    void emptyConfigIsInactiveAndAllowsEverything() {
        BrowserDomainPolicy policy = BrowserDomainPolicy.fromConfig("", "", "", "");
        assertThat(policy.isActive()).isFalse();
        assertThat(BrowserDomainPolicy.INACTIVE.isActive()).isFalse();
        assertThat(policy.check(AGENT_A, "https://anything.example/").allowed()).isTrue();
        // Even a URL that would be refused under an active policy passes - nothing is consulted.
        assertThat(policy.check(AGENT_A, "https://a@b.example/").allowed()).isTrue();
    }

    @Test
    void exactEntryMatchesOnlyThatHost() {
        BrowserDomainPolicy policy = allow("example.com");
        assertThat(policy.check(AGENT_A, "https://example.com/path?q=1").allowed()).isTrue();
        assertThat(policy.check(AGENT_A, "https://EXAMPLE.com:8443/").allowed()).isTrue();
        assertThat(policy.check(AGENT_A, "https://example.com./").allowed()).isTrue();
        assertThat(policy.check(AGENT_A, "https://www.example.com/").allowed()).isFalse();
        assertThat(policy.check(AGENT_A, "https://example.org/").allowed()).isFalse();
    }

    @Test
    void wildcardMatchesSubdomainsAtAnyDepthButNotTheApexOrLookalikes() {
        BrowserDomainPolicy policy = allow("*.example.com");
        assertThat(policy.check(AGENT_A, "https://a.example.com/").allowed()).isTrue();
        assertThat(policy.check(AGENT_A, "https://a.b.example.com/").allowed()).isTrue();
        assertThat(policy.check(AGENT_A, "https://example.com/").allowed()).isFalse();
        assertThat(policy.check(AGENT_A, "https://badexample.com/").allowed()).isFalse();
        assertThat(policy.check(AGENT_A, "https://example.com.evil.net/").allowed()).isFalse();
    }

    @Test
    void entriesMayBeWrittenAsUrlsAndAreNormalised() {
        BrowserDomainPolicy policy = allow("https://Example.COM/some/path, *.Docs.Example.com:443");
        assertThat(policy.check(AGENT_A, "https://example.com/").allowed()).isTrue();
        assertThat(policy.check(AGENT_A, "https://x.docs.example.com/").allowed()).isTrue();
    }

    @Test
    void blockedAlwaysWinsOverAllowed() {
        BrowserDomainPolicy policy =
                BrowserDomainPolicy.fromConfig(
                        "*.example.com,example.com", "ads.example.com", "", "");
        assertThat(policy.check(AGENT_A, "https://www.example.com/").allowed()).isTrue();
        BrowserDomainPolicy.Decision decision = policy.check(AGENT_A, "https://ads.example.com/");
        assertThat(decision.allowed()).isFalse();
        assertThat(decision.message()).contains("ads.example.com").contains("blocked");
    }

    @Test
    void blockedOnlyPolicyAllowsEverythingElse() {
        BrowserDomainPolicy policy = BrowserDomainPolicy.fromConfig("", "evil.example", "", "");
        assertThat(policy.isActive()).isTrue();
        assertThat(policy.hasAllowList(AGENT_A)).isFalse();
        assertThat(policy.check(AGENT_A, "https://fine.example/").allowed()).isTrue();
        assertThat(policy.check(AGENT_A, "https://evil.example/").allowed()).isFalse();
    }

    @Test
    void allowListAppliesToDocumentsOnlyWhileBlockedAlsoCoversSubresources() {
        BrowserDomainPolicy policy =
                BrowserDomainPolicy.fromConfig("example.com", "tracker.example", "", "");
        // A CDN asset on a page of an allowed domain is fine ...
        assertThat(policy.check(AGENT_A, "https://cdn.other.net/app.js", false).allowed()).isTrue();
        // ... the same host as a navigation/iframe/popup is not ...
        assertThat(policy.check(AGENT_A, "https://cdn.other.net/app.js", true).allowed()).isFalse();
        // ... and the blocked list covers sub-resources too.
        assertThat(policy.check(AGENT_A, "https://tracker.example/p.gif", false).allowed())
                .isFalse();
    }

    @Test
    void everyRedirectHopIsCheckedIndependently() {
        BrowserDomainPolicy policy = allow("start.example,mid.example");
        List<String> chain =
                List.of(
                        "https://start.example/a",
                        "https://mid.example/b",
                        "https://evil.example/c");
        List<Boolean> verdicts =
                chain.stream().map(url -> policy.check(AGENT_A, url, true).allowed()).toList();
        assertThat(verdicts).containsExactly(true, true, false);
    }

    @Test
    void unicodeAndPunycodeSpellingsAreTheSameHost() {
        BrowserDomainPolicy unicodeEntry = allow("bücher.de");
        assertThat(unicodeEntry.check(AGENT_A, "https://xn--bcher-kva.de/").allowed()).isTrue();
        assertThat(unicodeEntry.check(AGENT_A, "https://bücher.de/").allowed()).isTrue();
        BrowserDomainPolicy punycodeEntry = allow("xn--bcher-kva.de");
        assertThat(punycodeEntry.check(AGENT_A, "https://bücher.de/").allowed()).isTrue();
        assertThat(punycodeEntry.check(AGENT_A, "https://buecher.de/").allowed()).isFalse();
    }

    @Test
    void userinfoTrickIsRefusedWhicheverSideTheAllowedDomainIsOn() {
        BrowserDomainPolicy policy = allow("allowed.example");
        BrowserDomainPolicy.Decision a =
                policy.check(AGENT_A, "https://allowed.example@evil.example/");
        assertThat(a.allowed()).isFalse();
        assertThat(a.message()).contains("credentials");
        assertThat(policy.check(AGENT_A, "https://evil.example@allowed.example/").allowed())
                .isFalse();
        assertThat(policy.check(AGENT_A, "https://user:pw@allowed.example/").allowed()).isFalse();
    }

    @Test
    void malformedAndEvasiveUrlsFailClosed() {
        BrowserDomainPolicy policy = allow("allowed.example");
        assertThat(policy.check(AGENT_A, "https://evil.example\\@allowed.example/").allowed())
                .isFalse();
        assertThat(policy.check(AGENT_A, "https://allowed.example%2eevil.example/").allowed())
                .isFalse();
        assertThat(policy.check(AGENT_A, "https://allowed.example.evil.example/").allowed())
                .isFalse();
        assertThat(policy.check(AGENT_A, "https://allowed.example＠evil.example/").allowed())
                .isFalse();
        assertThat(policy.check(AGENT_A, "not a url").allowed()).isFalse();
        assertThat(policy.check(AGENT_A, "").allowed()).isFalse();
        assertThat(policy.check(AGENT_A, null).allowed()).isFalse();
        assertThat(policy.check(AGENT_A, "https:///path-without-host").allowed()).isFalse();
    }

    @Test
    void agentAllowListNarrowsTheOperatorCeiling() {
        Map<UUID, List<String>> agentAllowed = Map.of(AGENT_A, List.of("a.example"));
        BrowserDomainPolicy policy =
                new BrowserDomainPolicy(
                        List.of("a.example", "b.example"), List.of(), agentAllowed, Map.of());
        assertThat(policy.check(AGENT_A, "https://a.example/").allowed()).isTrue();
        assertThat(policy.check(AGENT_A, "https://b.example/").allowed()).isFalse();
        // An agent with no entry of its own is bound only by the operator list.
        assertThat(policy.check(AGENT_B, "https://b.example/").allowed()).isTrue();
        assertThat(policy.check(AGENT_B, "https://c.example/").allowed()).isFalse();
        assertThat(policy.hasAllowList(AGENT_A)).isTrue();
    }

    @Test
    void agentListCannotWidenBeyondTheOperatorCeiling() {
        BrowserDomainPolicy policy =
                new BrowserDomainPolicy(
                        List.of("a.example"),
                        List.of(),
                        Map.of(AGENT_A, List.of("a.example", "z.example")),
                        Map.of());
        assertThat(policy.check(AGENT_A, "https://z.example/").allowed()).isFalse();
    }

    @Test
    void perAgentBlockedListAppliesOnlyToThatAgent() {
        BrowserDomainPolicy policy =
                BrowserDomainPolicy.fromConfig("", "", "", AGENT_A + "=news.example");
        assertThat(policy.check(AGENT_A, "https://news.example/").allowed()).isFalse();
        assertThat(policy.check(AGENT_B, "https://news.example/").allowed()).isTrue();
    }

    @Test
    void agentMapParsingSkipsMalformedEntries() {
        Map<UUID, List<String>> parsed =
                BrowserDomainPolicy.parseAgentMap(
                        AGENT_A
                                + "=a.example,b.example;not-a-uuid=c.example;garbage;"
                                + AGENT_B
                                + "=d.example");
        assertThat(parsed).containsOnlyKeys(AGENT_A, AGENT_B);
        assertThat(parsed.get(AGENT_A)).containsExactly("a.example", "b.example");
    }

    @Test
    void namedMapParsingUpperCasesSecretNamesAndNormalisesHosts() {
        Map<String, List<String>> parsed =
                BrowserDomainPolicy.parseNamedMap(
                        "portal_pw=Login.Example.com, *.corp.example;OTHER=x.example");
        assertThat(parsed.get("PORTAL_PW")).containsExactly("login.example.com", "*.corp.example");
        assertThat(parsed.get("OTHER")).containsExactly("x.example");
    }

    @Test
    void starMatchesEveryHost() {
        assertThat(BrowserDomainPolicy.hostMatches(List.of("*"), "anything.example")).isTrue();
        assertThat(BrowserDomainPolicy.hostMatches(List.of("a.example"), null)).isFalse();
    }

    @Test
    void messageNamesTheHostButNeverThePathOrQuery() {
        BrowserDomainPolicy policy = allow("example.com");
        BrowserDomainPolicy.Decision decision =
                policy.check(AGENT_A, "https://other.example/private/path?token=abc123");
        assertThat(decision.allowed()).isFalse();
        assertThat(decision.message())
                .contains("other.example")
                .doesNotContain("abc123")
                .doesNotContain("/private");
    }
}
