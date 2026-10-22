package com.agenticai.core.security.edition;

import java.util.Locale;
import org.springframework.core.env.PropertyResolver;

/**
 * A capability of the platform that a lean (personal) install does not carry. Gated beans are
 * marked with {@link ConditionalOnEditionFeature}; this enum is the only list of features and the
 * only place the on/off decision is made, for the bean conditions, {@link EditionInfo} and the web
 * build alike.
 *
 * <p>Resolution order for one feature:
 *
 * <ol>
 *   <li>The explicit escape hatch {@code app.edition.features.<kebab-name>.enabled} (for example
 *       {@code APP_EDITION_FEATURES_CLOUD_SPEECH_ENABLED=true}). Read through the environment's
 *       boolean conversion, like {@link BillingEditionCondition}, so {@code 0}, {@code off} and
 *       {@code no} work. A blank value counts as unset.
 *   <li>{@link #BILLING_PAYMENTS} only: the pre-existing {@code app.edition.billing-enabled}
 *       (default {@code true}) stays authoritative, so the plan ports and the leaf beans can never
 *       disagree.
 *   <li>Otherwise the in-code table: tier {@code personal} is OFF, any other tier (or none) is ON.
 * </ol>
 *
 * <p>Beans are decided once, at boot: flipping a flag needs a core restart. That is deliberate:
 * there is no second, runtime source of truth to keep in sync.
 *
 * <p>Wiring rule: a gated bean may be injected only by beans gated by the SAME feature, or through
 * {@code ObjectProvider}, {@code Optional} or a collection. {@code EditionClosureRulesTest}
 * enforces it from bytecode.
 */
public enum EditionFeature {
    /** Payment gateways, checkout, invoices, top-ups and the plan-lifecycle schedulers. */
    BILLING_PAYMENTS,
    /** Slack, Discord, Teams and X channel adapters and their webhooks. */
    SOCIAL_CHANNELS,
    /** The public agent-to-agent directory and card surface. */
    A2A_DIRECTORY,
    /** The platform exposed as an MCP server to outside clients. */
    MCP_SERVER_EXPOSURE,
    /** Operator-only console surfaces (tenants, plans, fleet-wide controls). */
    OPERATOR_CONSOLE,
    /**
     * Cloud speech providers (Sarvam, ElevenLabs). An add-on on personal: flipped on by env +
     * restart.
     */
    CLOUD_SPEECH,
    /** Anonymous realtime voice on public share links. */
    VOICE_REALTIME_SHARE,
    /** Vertex video generation and the demo-media controllers. */
    VIDEO_DEMO_MEDIA;

    /** Prefix of every explicit per-feature override. */
    public static final String FEATURE_PROPERTY_PREFIX = "app.edition.features.";

    /** {@code cloud-speech}: the name used in the property, the API and the web build. */
    public String id() {
        return name().toLowerCase(Locale.ROOT).replace('_', '-');
    }

    /** The explicit override property, {@code app.edition.features.<id>.enabled}. */
    public String propertyName() {
        return FEATURE_PROPERTY_PREFIX + id() + ".enabled";
    }

    /** Whether this feature is on for the environment, following the resolution order above. */
    public boolean isEnabled(PropertyResolver environment) {
        Boolean explicit = environment.getProperty(propertyName(), Boolean.class);
        if (explicit != null) {
            return explicit;
        }
        if (this == BILLING_PAYMENTS) {
            return Boolean.TRUE.equals(
                    environment.getProperty(
                            ConditionalOnBillingEdition.PROPERTY, Boolean.class, Boolean.TRUE));
        }
        return EditionTier.of(environment) != EditionTier.PERSONAL;
    }
}
