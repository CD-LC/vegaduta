package com.agenticai.core.security.edition;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotatedBeanDefinitionReader;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

/**
 * Evaluates the real {@code @Conditional} on one probe bean per {@link EditionFeature} against the
 * property combinations the edition contract defines. Registration only evaluates conditions, so no bean is
 * instantiated and no database or Redis is needed.
 */
class EditionFeatureConditionTest {

    @ConditionalOnEditionFeature(EditionFeature.BILLING_PAYMENTS)
    static class BillingProbe {}

    @ConditionalOnEditionFeature(EditionFeature.SOCIAL_CHANNELS)
    static class SocialProbe {}

    @ConditionalOnEditionFeature(EditionFeature.A2A_DIRECTORY)
    static class A2aProbe {}

    @ConditionalOnEditionFeature(EditionFeature.MCP_SERVER_EXPOSURE)
    static class McpProbe {}

    @ConditionalOnEditionFeature(EditionFeature.OPERATOR_CONSOLE)
    static class OperatorProbe {}

    @ConditionalOnEditionFeature(EditionFeature.CLOUD_SPEECH)
    static class SpeechProbe {}

    @ConditionalOnEditionFeature(EditionFeature.VOICE_REALTIME_SHARE)
    static class VoiceProbe {}

    @ConditionalOnEditionFeature(EditionFeature.VIDEO_DEMO_MEDIA)
    static class VideoProbe {}

    private static final Map<EditionFeature, Class<?>> PROBES = Map.of(
            EditionFeature.BILLING_PAYMENTS, BillingProbe.class,
            EditionFeature.SOCIAL_CHANNELS, SocialProbe.class,
            EditionFeature.A2A_DIRECTORY, A2aProbe.class,
            EditionFeature.MCP_SERVER_EXPOSURE, McpProbe.class,
            EditionFeature.OPERATOR_CONSOLE, OperatorProbe.class,
            EditionFeature.CLOUD_SPEECH, SpeechProbe.class,
            EditionFeature.VOICE_REALTIME_SHARE, VoiceProbe.class,
            EditionFeature.VIDEO_DEMO_MEDIA, VideoProbe.class);

    private static boolean registered(EditionFeature feature, Map<String, Object> props) {
        GenericApplicationContext ctx = new GenericApplicationContext();
        StandardEnvironment env = new StandardEnvironment();
        env.getPropertySources().addFirst(new MapPropertySource("test", props));
        ctx.setEnvironment(env);
        new AnnotatedBeanDefinitionReader(ctx).register(PROBES.get(feature));
        try (ctx) {
            return List.of(ctx.getBeanDefinitionNames()).stream()
                    .anyMatch(n -> PROBES.get(feature).getName().equals(ctx.getBeanDefinition(n).getBeanClassName()));
        }
    }

    @Test
    void theProbesCoverEveryFeature() {
        assertThat(PROBES.keySet()).containsExactlyInAnyOrder(EditionFeature.values());
    }

    @Test
    void noEditionPropertyKeepsEveryFeature() {
        for (EditionFeature feature : EditionFeature.values()) {
            assertThat(registered(feature, Map.of())).as("%s with no property", feature).isTrue();
        }
    }

    @Test
    void liteAndFullAndAnUnknownTierKeepEveryFeature() {
        for (String tier : List.of("lite", "full", "LITE", " Full ", "", "enterprise")) {
            for (EditionFeature feature : EditionFeature.values()) {
                assertThat(registered(feature, Map.of("app.edition.tier", tier)))
                        .as("%s with tier '%s'", feature, tier)
                        .isTrue();
            }
        }
    }

    @Test
    void personalDropsEveryFeatureExceptBillingWhichFollowsItsOwnProperty() {
        Map<String, Object> personal = Map.of("app.edition.tier", "personal");
        for (EditionFeature feature : EditionFeature.values()) {
            boolean expected = feature == EditionFeature.BILLING_PAYMENTS; // billing-enabled defaults to true
            assertThat(registered(feature, personal)).as("%s on personal", feature).isEqualTo(expected);
        }
        Map<String, Object> personalNoBilling = Map.of("app.edition.tier", "personal", "app.edition.billing-enabled", "false");
        for (EditionFeature feature : EditionFeature.values()) {
            assertThat(registered(feature, personalNoBilling)).as("%s on personal, billing off", feature).isFalse();
        }
    }

    @Test
    void tierIsReadIgnoringCaseAndPadding() {
        for (String tier : List.of("personal", "PERSONAL", " Personal ")) {
            assertThat(registered(EditionFeature.SOCIAL_CHANNELS, Map.of("app.edition.tier", tier)))
                    .as("tier '%s'", tier)
                    .isFalse();
        }
    }

    @Test
    void anExplicitFlagWinsBothWays() {
        for (EditionFeature feature : EditionFeature.values()) {
            String property = "app.edition.features." + feature.id() + ".enabled";
            assertThat(property).isEqualTo(feature.propertyName());
            assertThat(registered(feature, Map.of("app.edition.tier", "personal", "app.edition.billing-enabled", "false", property, "true")))
                    .as("%s forced on", feature)
                    .isTrue();
            assertThat(registered(feature, Map.of(property, "false")))
                    .as("%s forced off on the default tier", feature)
                    .isFalse();
        }
    }

    @Test
    void theFlagNameIsKebabCase() {
        assertThat(EditionFeature.CLOUD_SPEECH.propertyName()).isEqualTo("app.edition.features.cloud-speech.enabled");
        assertThat(EditionFeature.VOICE_REALTIME_SHARE.id()).isEqualTo("voice-realtime-share");
    }

    @Test
    void billingPaymentsFollowsTheExistingBillingProperty() {
        assertThat(registered(EditionFeature.BILLING_PAYMENTS, Map.of("app.edition.billing-enabled", "false"))).isFalse();
        assertThat(registered(EditionFeature.BILLING_PAYMENTS, Map.of("app.edition.billing-enabled", "true"))).isTrue();
        // On personal, billing-enabled stays authoritative: the override path the install can still use.
        assertThat(registered(EditionFeature.BILLING_PAYMENTS, Map.of("app.edition.tier", "personal", "app.edition.billing-enabled", "true")))
                .isTrue();
        // A tier alone does not switch billing off on a non-personal tier.
        assertThat(registered(EditionFeature.BILLING_PAYMENTS, Map.of("app.edition.tier", "full"))).isTrue();
    }

    @Test
    void anExplicitBillingFlagBeatsTheBillingProperty() {
        assertThat(registered(
                        EditionFeature.BILLING_PAYMENTS,
                        Map.of("app.edition.billing-enabled", "true", "app.edition.features.billing-payments.enabled", "false")))
                .isFalse();
        assertThat(registered(
                        EditionFeature.BILLING_PAYMENTS,
                        Map.of("app.edition.billing-enabled", "false", "app.edition.features.billing-payments.enabled", "true")))
                .isTrue();
    }

    /** The boolean conversion every other edition switch uses, so the spellings agree everywhere. */
    @Test
    void everyBooleanSpellingIsUnderstood() {
        for (String off : List.of("false", "FALSE", "0", "off", "no")) {
            assertThat(registered(EditionFeature.CLOUD_SPEECH, Map.of("app.edition.features.cloud-speech.enabled", off)))
                    .as("flag=%s", off)
                    .isFalse();
            assertThat(registered(EditionFeature.BILLING_PAYMENTS, Map.of("app.edition.billing-enabled", off)))
                    .as("billing-enabled=%s", off)
                    .isFalse();
        }
        for (String on : List.of("true", "TRUE", "1", "on", "yes")) {
            assertThat(registered(
                            EditionFeature.CLOUD_SPEECH,
                            Map.of("app.edition.tier", "personal", "app.edition.features.cloud-speech.enabled", on)))
                    .as("flag=%s", on)
                    .isTrue();
        }
    }

    @Test
    void aBlankFlagCountsAsUnset() {
        // Compose writes APP_EDITION_FEATURES_X_ENABLED: ${...:-} as an empty string when the operator sets nothing.
        assertThat(registered(EditionFeature.CLOUD_SPEECH, Map.of("app.edition.tier", "personal", "app.edition.features.cloud-speech.enabled", "")))
                .isFalse();
        assertThat(registered(EditionFeature.CLOUD_SPEECH, Map.of("app.edition.features.cloud-speech.enabled", ""))).isTrue();
    }

    @Test
    void editionInfoReportsTheSameDecisionsTheConditionsMake() {
        StandardEnvironment env = new StandardEnvironment();
        env.getPropertySources()
                .addFirst(new MapPropertySource(
                        "test",
                        Map.of(
                                "app.edition.tier", "personal",
                                "app.edition.billing-enabled", "false",
                                "app.auth.mode", " LOCAL ",
                                "app.edition.features.cloud-speech.enabled", "true")));

        EditionInfo info = EditionInfo.from(env);

        assertThat(info.tier()).isEqualTo("personal");
        assertThat(info.billingEnabled()).isFalse();
        assertThat(info.authMode()).isEqualTo("local");
        assertThat(info.features()).containsEntry("cloud-speech", true).containsEntry("social-channels", false);
        assertThat(info.features().keySet())
                .containsExactlyElementsOf(List.of(EditionFeature.values()).stream().map(EditionFeature::id).toList());
        for (EditionFeature feature : EditionFeature.values()) {
            assertThat(info.features()).as(feature.id()).containsEntry(feature.id(), registered(feature, env));
        }
    }

    @Test
    void editionInfoDefaultsAreFullWithKeycloak() {
        EditionInfo info = EditionInfo.from(new StandardEnvironment());

        assertThat(info.tier()).isEqualTo("full");
        assertThat(info.billingEnabled()).isTrue();
        assertThat(info.authMode()).isEqualTo("keycloak");
        assertThat(info.features().values()).containsOnly(true);
    }

    private static boolean registered(EditionFeature feature, StandardEnvironment env) {
        GenericApplicationContext ctx = new GenericApplicationContext();
        ctx.setEnvironment(env);
        new AnnotatedBeanDefinitionReader(ctx).register(PROBES.get(feature));
        try (ctx) {
            return List.of(ctx.getBeanDefinitionNames()).stream()
                    .anyMatch(n -> PROBES.get(feature).getName().equals(ctx.getBeanDefinition(n).getBeanClassName()));
        }
    }
}
