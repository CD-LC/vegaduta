package com.agenticai.core.security.edition;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

/**
 * {@link EditionFeature}'s resolution order: the explicit per-feature escape hatch first, then
 * {@code app.edition.billing-enabled} for {@link EditionFeature#BILLING_PAYMENTS} only, then the
 * tier table (personal off, everything else on).
 */
class EditionFeatureTest {

    private static MockEnvironment env() {
        return new MockEnvironment();
    }

    @Test
    void idIsTheKebabCaseFormUsedInPropertiesAndTheApi() {
        assertThat(EditionFeature.BILLING_PAYMENTS.id()).isEqualTo("billing-payments");
        assertThat(EditionFeature.SOCIAL_CHANNELS.id()).isEqualTo("social-channels");
        assertThat(EditionFeature.MCP_SERVER_EXPOSURE.id()).isEqualTo("mcp-server-exposure");
        assertThat(EditionFeature.VOICE_REALTIME_SHARE.id()).isEqualTo("voice-realtime-share");
        assertThat(EditionFeature.VIDEO_DEMO_MEDIA.id()).isEqualTo("video-demo-media");
        assertThat(EditionFeature.A2A_DIRECTORY.id()).isEqualTo("a2a-directory");
        assertThat(EditionFeature.OPERATOR_CONSOLE.id()).isEqualTo("operator-console");
        assertThat(EditionFeature.CLOUD_SPEECH.id()).isEqualTo("cloud-speech");
    }

    @Test
    void propertyNameIsTheFeaturePrefixPlusId() {
        assertThat(EditionFeature.CLOUD_SPEECH.propertyName())
                .isEqualTo("app.edition.features.cloud-speech.enabled");
    }

    @Test
    void theExplicitPerFeatureOverrideWinsOverTheTier() {
        MockEnvironment env =
                env()
                        .withProperty(EditionTier.PROPERTY, "personal")
                        .withProperty("app.edition.features.cloud-speech.enabled", "true");

        assertThat(EditionFeature.CLOUD_SPEECH.isEnabled(env)).isTrue();
        // The override is per-feature: a neighbour with no override still follows the tier.
        assertThat(EditionFeature.VOICE_REALTIME_SHARE.isEnabled(env)).isFalse();
    }

    @Test
    void anExplicitFalseTurnsAFeatureOffOnTheDefaultFullTier() {
        MockEnvironment env =
                env().withProperty("app.edition.features.social-channels.enabled", "false");

        assertThat(EditionFeature.SOCIAL_CHANNELS.isEnabled(env)).isFalse();
        assertThat(EditionFeature.A2A_DIRECTORY.isEnabled(env)).isTrue();
    }

    @Test
    void everyFeatureDefaultsOffOnPersonalAndOnOtherwise() {
        MockEnvironment personal = env().withProperty(EditionTier.PROPERTY, "personal");
        for (EditionFeature feature : EditionFeature.values()) {
            if (feature == EditionFeature.BILLING_PAYMENTS) {
                continue; // has its own switch, asserted below
            }
            assertThat(feature.isEnabled(personal)).as(feature.name()).isFalse();
            assertThat(feature.isEnabled(env())).as(feature.name()).isTrue();
        }
    }

    @Test
    void billingPaymentsKeepsAnsweringFromItsOwnPreExistingProperty() {
        // Even on personal, billing stays on unless its dedicated property says otherwise -
        // the plan ports and the leaf beans must never disagree.
        MockEnvironment personal = env().withProperty(EditionTier.PROPERTY, "personal");
        assertThat(EditionFeature.BILLING_PAYMENTS.isEnabled(personal)).isTrue();

        MockEnvironment off =
                env().withProperty(ConditionalOnBillingEdition.PROPERTY, "false");
        assertThat(EditionFeature.BILLING_PAYMENTS.isEnabled(off)).isFalse();
    }
}
