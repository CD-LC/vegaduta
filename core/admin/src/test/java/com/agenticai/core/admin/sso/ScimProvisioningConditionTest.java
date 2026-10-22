package com.agenticai.core.admin.sso;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.agenticai.core.admin.identity.IdentityUserAdminPort;
import com.agenticai.core.security.TenantLimits;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/**
 * {@code /scim/v2/**} is {@code permitAll}; the only thing behind it is a {@code vdscim_*} bearer
 * token that {@code TenantSsoController} (Keycloak-only) mints. On a personal edition ({@code
 * app.edition.tier=personal}) no token can exist, so the controller must not be registered at all
 * and {@code /scim/v2/**} answers 404. Lite, full and an unset edition keep the controller exactly
 * as before, whatever the identity provider is.
 */
class ScimProvisioningConditionTest {

    @Configuration(proxyBeanMethods = false)
    @Import(ScimProvisioningController.class)
    static class Wiring {
        @Bean
        TenantScimTokenRepository tokenRepository() {
            return mock(TenantScimTokenRepository.class);
        }

        @Bean
        IdentityUserAdminPort identityUserAdminPort() {
            return mock(IdentityUserAdminPort.class);
        }

        @Bean
        TenantLimits tenantLimits() {
            return mock(TenantLimits.class);
        }
    }

    private ApplicationContextRunner runner() {
        return new ApplicationContextRunner()
                .withInitializer(ctx -> ctx.getEnvironment().addActiveProfile("security"))
                .withUserConfiguration(Wiring.class);
    }

    @Test
    void absentOnAPersonalEdition() {
        runner().withPropertyValues("app.edition.tier=personal")
                .run(ctx -> assertThat(ctx).doesNotHaveBean(ScimProvisioningController.class));
    }

    @Test
    void presentOnLiteAndFullWhateverTheIdentityProvider() {
        for (String tier : new String[] {"lite", "full"}) {
            for (String provider : new String[] {"local", "keycloak", "auth0", ""}) {
                runner().withPropertyValues(
                                "app.edition.tier=" + tier, "app.identity.provider=" + provider)
                        .run(
                                ctx ->
                                        assertThat(ctx)
                                                .as("tier=%s provider=%s", tier, provider)
                                                .hasSingleBean(ScimProvisioningController.class));
            }
        }
    }

    @Test
    void presentWhenNoEditionIsSetEvenOnTheLocalIdentityProvider() {
        runner().withPropertyValues("app.identity.provider=local")
                .run(ctx -> assertThat(ctx).hasSingleBean(ScimProvisioningController.class));
        runner().run(ctx -> assertThat(ctx).hasSingleBean(ScimProvisioningController.class));
    }

    @Test
    void anExplicitFeatureOverrideCanSwitchItBackOnForPersonal() {
        runner().withPropertyValues(
                        "app.edition.tier=personal",
                        "app.edition.features.operator-console.enabled=true")
                .run(ctx -> assertThat(ctx).hasSingleBean(ScimProvisioningController.class));
    }

    @Test
    void stillBehindTheSecurityProfile() {
        new ApplicationContextRunner()
                .withUserConfiguration(Wiring.class)
                .run(ctx -> assertThat(ctx).doesNotHaveBean(ScimProvisioningController.class));
    }
}
