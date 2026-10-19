package com.agenticai.core.agents.tools.browseruse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Domain normalization and the storageState scoping rules that make a browser profile
 * domain-scoped.
 */
class BrowserStorageStateTest {

    private static final String STATE =
            """
        {"cookies":[
          {"name":"sid","value":"s1","domain":"app.example.com","path":"/","expires":-1,"httpOnly":true,"secure":true,"sameSite":"Lax"},
          {"name":"parent","value":"p1","domain":".example.com","path":"/","expires":-1,"httpOnly":false,"secure":true,"sameSite":"Lax"},
          {"name":"sub","value":"u1","domain":"other.example.com","path":"/","expires":-1,"httpOnly":false,"secure":true,"sameSite":"Lax"},
          {"name":"evil","value":"e1","domain":"evil.test","path":"/","expires":-1,"httpOnly":false,"secure":false,"sameSite":"None"},
          {"name":"suffix","value":"x1","domain":"notexample.com","path":"/","expires":-1,"httpOnly":false,"secure":false,"sameSite":"None"}
        ],
        "origins":[
          {"origin":"https://app.example.com","localStorage":[{"name":"token","value":"t1"}]},
          {"origin":"https://evil.test","localStorage":[{"name":"x","value":"y"}]}
        ],
        "unexpected":"dropped"}
        """;

    @Test
    void normalizesPlausibleDomains() {
        assertThat(BrowserStorageState.normalizeDomain("  App.Example.COM "))
                .isEqualTo("app.example.com");
        assertThat(BrowserStorageState.normalizeDomain("*.example.com")).isEqualTo("example.com");
        assertThat(BrowserStorageState.normalizeDomain(".example.com.")).isEqualTo("example.com");
        assertThat(BrowserStorageState.normalizeDomain("https://app.example.com:8443/login?x=1"))
                .isEqualTo("app.example.com");
        assertThat(BrowserStorageState.normalizeDomain("app.example.com/path"))
                .isEqualTo("app.example.com");
    }

    @Test
    void refusesThingsThatAreNotAPublicHostname() {
        for (String bad :
                new String[] {
                    "",
                    "  ",
                    "com",
                    "localhost",
                    "10.0.0.1",
                    "192.168.1.5",
                    "exa mple.com",
                    "-bad.example.com",
                    "a..b.com"
                }) {
            assertThatThrownBy(() -> BrowserStorageState.normalizeDomain(bad))
                    .as(bad)
                    .isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> BrowserStorageState.normalizeDomains(List.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> BrowserStorageState.normalizeDomains(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void normalizeDomainsDeduplicatesKeepingOrder() {
        assertThat(
                        BrowserStorageState.normalizeDomains(
                                List.of("B.example.com", "a.example.com", "b.example.com")))
                .containsExactly("b.example.com", "a.example.com");
    }

    @Test
    void normalizeDomainsCapsTheNumberOfAllowedDomains() {
        List<String> atCap =
                java.util.stream.IntStream.rangeClosed(1, BrowserStorageState.MAX_ALLOWED_DOMAINS)
                        .mapToObj(i -> "site" + i + ".example.com")
                        .toList();
        assertThat(BrowserStorageState.normalizeDomains(atCap))
                .hasSize(BrowserStorageState.MAX_ALLOWED_DOMAINS);

        List<String> overCap =
                java.util.stream.IntStream.rangeClosed(
                                1, BrowserStorageState.MAX_ALLOWED_DOMAINS + 1)
                        .mapToObj(i -> "site" + i + ".example.com")
                        .toList();
        assertThatThrownBy(() -> BrowserStorageState.normalizeDomains(overCap))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("at most");
    }

    @Test
    void hostAllowedMatchesTheDomainAndItsSubdomainsOnly() {
        List<String> allowed = List.of("example.com");

        assertThat(BrowserStorageState.hostAllowed("example.com", allowed)).isTrue();
        assertThat(BrowserStorageState.hostAllowed("APP.Example.com", allowed)).isTrue();
        assertThat(BrowserStorageState.hostAllowed("notexample.com", allowed)).isFalse();
        assertThat(BrowserStorageState.hostAllowed("example.com.evil.test", allowed)).isFalse();
        assertThat(BrowserStorageState.hostAllowed(null, allowed)).isFalse();
        assertThat(BrowserStorageState.hostAllowed("example.com", List.of())).isFalse();
    }

    @Test
    void httpHostOfOnlyAcceptsWebUrls() {
        assertThat(BrowserStorageState.httpHostOf("https://App.Example.com/x"))
                .isEqualTo("app.example.com");
        assertThat(BrowserStorageState.httpHostOf("about:blank")).isNull();
        assertThat(BrowserStorageState.httpHostOf("data:text/html,hi")).isNull();
        assertThat(BrowserStorageState.httpHostOf("not a url")).isNull();
        assertThat(BrowserStorageState.httpHostOf(null)).isNull();
    }

    @Test
    void filtersCookiesAndOriginsToTheScope() {
        String filtered = BrowserStorageState.filterToDomains(STATE, List.of("app.example.com"));

        // in scope: the host's own cookie, and the PARENT-domain cookie that is also sent to it
        assertThat(filtered).contains("\"sid\"").contains("\"parent\"");
        // out of scope: a sibling subdomain, another site, a look-alike suffix
        assertThat(filtered)
                .doesNotContain("\"sub\"")
                .doesNotContain("evil")
                .doesNotContain("\"suffix\"");
        assertThat(filtered)
                .contains("https://app.example.com")
                .doesNotContain("https://evil.test");
        assertThat(filtered).doesNotContain("unexpected");
        assertThat(BrowserStorageState.entryCount(filtered)).isEqualTo(3);
    }

    @Test
    void aWiderScopeKeepsTheSubdomainCookieToo() {
        String filtered = BrowserStorageState.filterToDomains(STATE, List.of("example.com"));

        assertThat(filtered).contains("\"sid\"").contains("\"parent\"").contains("\"sub\"");
        assertThat(filtered).doesNotContain("evil").doesNotContain("\"suffix\"");
    }

    @Test
    void anEmptyScopeKeepsNothing() {
        String filtered = BrowserStorageState.filterToDomains(STATE, List.of());

        assertThat(BrowserStorageState.entryCount(filtered)).isZero();
    }

    @Test
    void rejectsStateThatIsNotAJsonObject() {
        assertThatThrownBy(
                        () ->
                                BrowserStorageState.filterToDomains(
                                        "not json", List.of("example.com")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(
                        () -> BrowserStorageState.filterToDomains("[1,2]", List.of("example.com")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void domainListRoundTripsThroughTheStorageColumn() {
        assertThat(
                        BrowserStorageState.splitDomains(
                                BrowserStorageState.joinDomains(
                                        List.of("a.example.com", "b.example.org"))))
                .containsExactly("a.example.com", "b.example.org");
        assertThat(BrowserStorageState.splitDomains(null)).isEmpty();
        assertThat(BrowserStorageState.splitDomains(" , ")).isEmpty();
    }
}
