package com.agenticai.core.omnichannel.meta;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;

/**
 * The guided WhatsApp connection is configured by {@code app.meta.embedded-signup.*} (application.yml, the
 * META_EMBEDDED_SIGNUP_* variables, and the reliability console's credential check) but the service read only
 * {@code app.omnichannel.meta.embedded-signup.*}, which nothing sets: with all four documented variables present
 * the button still never appeared. Both spellings now work, the old one winning.
 */
class MetaEmbeddedSignupConfigKeyTest {

    private static MetaEmbeddedSignupService service(Map<String, Object> properties) {
        try (AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext()) {
            ctx.getEnvironment().getPropertySources().addFirst(new MapPropertySource("test", properties));
            ctx.registerBean(MetaGraphApiClient.class, () -> mock(MetaGraphApiClient.class));
            ctx.registerBean(MetaWhatsAppCredentialService.class, () -> mock(MetaWhatsAppCredentialService.class));
            ctx.register(MetaEmbeddedSignupService.class);
            ctx.refresh();
            return ctx.getBean(MetaEmbeddedSignupService.class);
        }
    }

    @Test
    void theYmlKeysThatTheEnvironmentVariablesFeedConfigureTheService() {
        MetaEmbeddedSignupService service = service(Map.of(
            "app.meta.embedded-signup.app-id", "app",
            "app.meta.embedded-signup.app-secret", "secret",
            "app.meta.embedded-signup.config-id", "config",
            "app.meta.embedded-signup.redirect-uri", "https://api.example.com/cb"));

        assertThat(service.isConfigured()).isTrue();
        assertThat(service.config().configId()).isEqualTo("config");
    }

    @Test
    void theOmnichannelSpellingStillWorksAndWins() {
        MetaEmbeddedSignupService service = service(Map.of(
            "app.omnichannel.meta.embedded-signup.app-id", "new-app",
            "app.omnichannel.meta.embedded-signup.app-secret", "secret",
            "app.omnichannel.meta.embedded-signup.config-id", "new-config",
            "app.omnichannel.meta.embedded-signup.redirect-uri", "https://api.example.com/cb",
            "app.meta.embedded-signup.app-id", "old-app",
            "app.meta.embedded-signup.config-id", "old-config"));

        assertThat(service.isConfigured()).isTrue();
        assertThat(service.config().appId()).isEqualTo("new-app");
        assertThat(service.config().configId()).isEqualTo("new-config");
    }

    @Test
    void nothingConfiguredMeansNotConfigured() {
        assertThat(service(Map.of()).isConfigured()).isFalse();
    }
}
