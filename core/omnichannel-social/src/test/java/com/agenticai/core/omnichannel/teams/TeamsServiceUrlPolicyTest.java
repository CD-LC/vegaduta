package com.agenticai.core.omnichannel.teams;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * SEC-047: the Bot Framework serviceUrl is where the bot's Entra bearer token is sent, so an
 * attacker-chosen host is a token leak. {@link TeamsServiceUrlPolicy#isAllowed} is the host
 * allowlist that stands in front of every reply; {@link TeamsServiceUrlPolicy#normalise} is the
 * canonical form the JWT {@code serviceurl} claim is compared against.
 */
class TeamsServiceUrlPolicyTest {

    private final TeamsServiceUrlPolicy policy =
            new TeamsServiceUrlPolicy(TeamsServiceUrlPolicy.DEFAULT_ALLOWED_HOSTS);

    @Test
    void theDefaultListAllowsMicrosoftConnectorHosts() {
        assertThat(policy.isAllowed("https://smba.trafficmanager.net/amer/")).isTrue();
        assertThat(policy.isAllowed("https://eastus.botframework.com/v3/")).isTrue();
        assertThat(policy.isAllowed("https://api.teams.microsoft.com/x")).isTrue();
    }

    @Test
    void wildcardEntriesMatchStrictSubdomainsOnly() {
        assertThat(policy.isAllowed("https://a.b.botframework.com/")).isTrue();
        // "*.example.com" matches strict subdomains - not the apex itself, and not a
        // lookalike suffix.
        assertThat(policy.isAllowed("https://botframework.com/")).isFalse();
        assertThat(policy.isAllowed("https://evilbotframework.com/")).isFalse();
    }

    @Test
    void smbaIsListedExactlyBecauseTrafficmanagerIsCustomerControlled() {
        // Any Azure customer can create <anything>.trafficmanager.net; only smba is allowed.
        assertThat(policy.isAllowed("https://attacker.trafficmanager.net/")).isFalse();
    }

    @Test
    void nonHttpsUserinfoAndNonDefaultPortsAreRejected() {
        assertThat(policy.isAllowed("http://smba.trafficmanager.net/")).isFalse();
        assertThat(policy.isAllowed("https://user:pw@smba.trafficmanager.net/")).isFalse();
        assertThat(policy.isAllowed("https://smba.trafficmanager.net:8443/")).isFalse();
        // The explicit default port is fine.
        assertThat(policy.isAllowed("https://smba.trafficmanager.net:443/")).isTrue();
    }

    @Test
    void unparseableMissingHostAndBlankValuesAreRejected() {
        assertThat(policy.isAllowed(null)).isFalse();
        assertThat(policy.isAllowed("")).isFalse();
        assertThat(policy.isAllowed("   ")).isFalse();
        assertThat(policy.isAllowed("not a url")).isFalse();
        assertThat(policy.isAllowed("https://")).isFalse();
    }

    @Test
    void hostMatchingIsCaseInsensitiveAndATrailingDotIsIgnored() {
        assertThat(policy.isAllowed("HTTPS://SMBA.TRAFFICMANAGER.NET./")).isTrue();
    }

    @Test
    void aCustomListReplacesTheDefaults() {
        TeamsServiceUrlPolicy narrow = new TeamsServiceUrlPolicy("smba.trafficmanager.net");

        assertThat(narrow.isAllowed("https://smba.trafficmanager.net/")).isTrue();
        assertThat(narrow.isAllowed("https://eastus.botframework.com/")).isFalse();
    }

    @Test
    void emptyEntriesAndNullConfigurationAreTolerated() {
        TeamsServiceUrlPolicy none = new TeamsServiceUrlPolicy(null);
        assertThat(none.isAllowed("https://smba.trafficmanager.net/")).isFalse();

        TeamsServiceUrlPolicy sparse = new TeamsServiceUrlPolicy(" , smba.trafficmanager.net ,,");
        assertThat(sparse.isAllowed("https://smba.trafficmanager.net/")).isTrue();
    }

    @Test
    void normaliseLowercasesSchemeAndHostDropsTheDefaultPortAndStripsTrailingSlashes() {
        assertThat(TeamsServiceUrlPolicy.normalise("HTTPS://SMBA.TrafficManager.NET:443/amer///"))
                .isEqualTo("https://smba.trafficmanager.net/amer");
        assertThat(TeamsServiceUrlPolicy.normalise("http://host.example:80/a/"))
                .isEqualTo("http://host.example/a");
    }

    @Test
    void normaliseKeepsNonDefaultPortsAndTheQueryString() {
        assertThat(TeamsServiceUrlPolicy.normalise("https://host.example:8443/a/?x=1"))
                .isEqualTo("https://host.example:8443/a?x=1");
    }

    @Test
    void normaliseReturnsNullForUnparseableOrHostlessInput() {
        assertThat(TeamsServiceUrlPolicy.normalise(null)).isNull();
        assertThat(TeamsServiceUrlPolicy.normalise(" ")).isNull();
        assertThat(TeamsServiceUrlPolicy.normalise("not a uri")).isNull();
        assertThat(TeamsServiceUrlPolicy.normalise("/relative/path")).isNull();
    }
}
