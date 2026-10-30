package com.agenticai.core.dbauth;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;

/**
 * Covers {@link DbAuthProperties}' shipped defaults and every setter the relaxed binder would
 * exercise - the defaults ARE configuration (a wrong default silently changes how core logs in to
 * Postgres), so they are pinned here rather than assumed.
 */
class DbAuthPropertiesTest {

    @Test
    void defaultsArePasswordModeWithATwoMinuteRefreshSkew() {
        DbAuthProperties p = new DbAuthProperties();

        assertThat(p.getAuth()).isEqualTo("password");
        assertThat(p.getTokenRefreshSkew()).isEqualTo(Duration.ofMinutes(2));
    }

    @Test
    void nestedDefaultsCarryThePublicCloudScopes() {
        DbAuthProperties p = new DbAuthProperties();

        assertThat(p.getAws().getRegion()).isEmpty();
        assertThat(p.getAzure().getScope())
                .isEqualTo("https://ossrdbms-aad.database.windows.net/.default");
        assertThat(p.getGcp().getScope())
                .isEqualTo("https://www.googleapis.com/auth/sqlservice.login");
    }

    @Test
    void settersRoundTripEveryBoundField() {
        DbAuthProperties p = new DbAuthProperties();

        p.setAuth("gcp-iam");
        p.setTokenRefreshSkew(Duration.ofSeconds(45));
        p.getAws().setRegion("ap-south-1");
        p.getAzure().setScope("custom-scope");
        p.getGcp().setScope("other-scope");

        assertThat(p.getAuth()).isEqualTo("gcp-iam");
        assertThat(p.getTokenRefreshSkew()).isEqualTo(Duration.ofSeconds(45));
        assertThat(p.getAws().getRegion()).isEqualTo("ap-south-1");
        assertThat(p.getAzure().getScope()).isEqualTo("custom-scope");
        assertThat(p.getGcp().getScope()).isEqualTo("other-scope");
    }
}
