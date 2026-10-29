package com.agenticai.core.security.edition;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

/**
 * {@link EditionInfo#from} is what the public status endpoint and the web console render - the
 * same code the bean conditions run, so the report can never disagree with the registered beans.
 */
class EditionInfoTest {

    @Test
    void theDefaultInstallReportsFullKeycloakAndEveryFeatureOn() {
        EditionInfo info = EditionInfo.from(new MockEnvironment());

        assertThat(info.tier()).isEqualTo("full");
        assertThat(info.billingEnabled()).isTrue();
        assertThat(info.authMode()).isEqualTo("keycloak");
        assertThat(info.features()).hasSize(EditionFeature.values().length);
        assertThat(info.features()).containsEntry("social-channels", true);
        assertThat(info.features()).containsEntry("billing-payments", true);
    }

    @Test
    void aPersonalLocalAuthInstallReportsItsOwnShape() {
        MockEnvironment env =
                new MockEnvironment()
                        .withProperty(EditionTier.PROPERTY, "Personal")
                        .withProperty(EditionInfo.AUTH_MODE_PROPERTY, " Local ")
                        .withProperty(ConditionalOnBillingEdition.PROPERTY, "false")
                        .withProperty("app.edition.features.cloud-speech.enabled", "true");

        EditionInfo info = EditionInfo.from(env);

        assertThat(info.tier()).isEqualTo("personal");
        assertThat(info.billingEnabled()).isFalse();
        assertThat(info.authMode()).isEqualTo("local");
        assertThat(info.features()).containsEntry("cloud-speech", true);
        assertThat(info.features()).containsEntry("social-channels", false);
    }

    @Test
    void aBlankAuthModeFallsBackToKeycloak() {
        EditionInfo info =
                EditionInfo.from(
                        new MockEnvironment().withProperty(EditionInfo.AUTH_MODE_PROPERTY, " "));

        assertThat(info.authMode()).isEqualTo("keycloak");
    }

    @Test
    void theFeaturesMapIsDefensivelyCopiedAndImmutable() {
        Map<String, Boolean> features = new java.util.LinkedHashMap<>();
        features.put("x", true);

        EditionInfo info = new EditionInfo("full", true, "keycloak", features);
        features.put("y", false);

        assertThat(info.features()).hasSize(1);
        assertThatThrownBy(() -> info.features().put("z", true))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
