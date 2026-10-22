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
 * {@code /scim/v2/**} is {@code permitAll}; the only thing behind it is a {@code vdscim_*} bearer token that
 * {@code TenantSsoController} (Keycloak-only) mints. On a personal install ({@code app.identity.provider=local})
 * no token can exist, so the controller must not be registered at all and {@code /scim/v2/**} answers 404.
 * Every other provider value, and the property unset, keeps the controller exactly as before.
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
    void absentOnTheLocalIdentityProvider() {
        runner().withPropertyValues("app.identity.provider=local")
                .run(ctx -> assertThat(ctx).doesNotHaveBean(ScimProvisioningController.class));
    }

    @Test
    void presentOnKeycloak() {
        runner().withPropertyValues("app.identity.provider=keycloak")
                .run(ctx -> assertThat(ctx).hasSingleBean(ScimProvisioningController.class));
    }

    @Test
    void presentWhenTheProviderIsUnset() {
        runner().run(ctx -> assertThat(ctx).hasSingleBean(ScimProvisioningController.class));
    }

    @Test
    void presentWhenTheProviderIsBlank() {
        runner().withPropertyValues("app.identity.provider=")
                .run(ctx -> assertThat(ctx).hasSingleBean(ScimProvisioningController.class));
    }

    @Test
    void presentOnAuth0WhichSuppliesTheUserAdminPort() {
        runner().withPropertyValues("app.identity.provider=auth0")
                .run(ctx -> assertThat(ctx).hasSingleBean(ScimProvisioningController.class));
    }

    @Test
    void stillBehindTheSecurityProfile() {
        new ApplicationContextRunner()
                .withUserConfiguration(Wiring.class)
                .run(ctx -> assertThat(ctx).doesNotHaveBean(ScimProvisioningController.class));
    }
}
