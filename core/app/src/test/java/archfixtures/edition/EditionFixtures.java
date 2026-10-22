package archfixtures.edition;

import com.agenticai.core.security.edition.ConditionalOnEditionFeature;
import com.agenticai.core.security.edition.EditionFeature;
import jakarta.annotation.Nullable;
import java.util.List;
import java.util.Optional;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.stereotype.Component;

/**
 * Bytecode fixtures for {@code EditionWiringTest} and {@code EditionClosureRulesTest}: gated beans,
 * beans that may and may not inject them. Never instantiated by Spring - this package is
 * deliberately outside {@code com.agenticai.core}, the component-scan root of every application and
 * test context, so the deliberately broken ones here cannot break a context. Only read as bytecode
 * (and registered by hand in the wiring test, where only conditions are evaluated).
 */
@SuppressWarnings("unused")
public final class EditionFixtures {

    private EditionFixtures() {}

    // ---- gated beans -------------------------------------------------------------------------

    @Component
    @ConditionalOnEditionFeature(EditionFeature.SOCIAL_CHANNELS)
    public static class GatedSocial {}

    @Component
    @ConditionalOnEditionFeature(EditionFeature.CLOUD_SPEECH)
    public static class GatedSpeech {}

    /** Billing follows its own property as well as the tier. */
    @Component
    @ConditionalOnEditionFeature(EditionFeature.BILLING_PAYMENTS)
    public static class GatedBilling {}

    // ---- allowed ------------------------------------------------------------------------------

    /** Same feature may inject it directly. */
    @Component
    @ConditionalOnEditionFeature(EditionFeature.SOCIAL_CHANNELS)
    public static class SameFeatureConsumer {
        public SameFeatureConsumer(GatedSocial social) {}
    }

    /** Lazy and optional access is the sanctioned way for ungated code to reach a gated bean. */
    @Component
    public static class OptionalAccess {
        public OptionalAccess(
                ObjectProvider<GatedSocial> provider,
                Optional<GatedSocial> optional,
                List<GatedSocial> all,
                @Nullable GatedSocial nullable) {}
    }

    /** A field injection that does not require the bean. */
    @Component
    public static class NotRequiredField {
        @Autowired(required = false)
        GatedSocial social;
    }

    /** An ungated bean may use the gated bean's feature id; only the TYPE is a dependency. */
    @Component
    public static class NoDependency {
        public NoDependency() {}
    }

    /** A @Bean method gated like its dependency. */
    @Configuration
    public static class ConfigWithGatedBeanMethod {
        @Bean
        @ConditionalOnEditionFeature(EditionFeature.SOCIAL_CHANNELS)
        public String gatedBean(GatedSocial social) {
            return "ok";
        }
    }

    // ---- reported by the closure rule ---------------------------------------------------------

    @Component
    public static class UngatedConstructorInjection {
        public UngatedConstructorInjection(GatedSocial social) {}
    }

    @Component
    @ConditionalOnEditionFeature(EditionFeature.CLOUD_SPEECH)
    public static class OtherFeatureConstructorInjection {
        public OtherFeatureConstructorInjection(GatedSocial social) {}
    }

    @Component
    public static class UngatedFieldInjection {
        @Autowired GatedSpeech speech;
    }

    @Component
    public static class UngatedSetterInjection {
        @Autowired
        void setSpeech(GatedSpeech speech) {}
    }

    @Component
    public static class UngatedBillingConsumer {
        public UngatedBillingConsumer(GatedBilling billing) {}
    }

    @Configuration
    public static class ConfigWithUngatedBeanMethod {
        @Bean
        public Object ungatedBean(GatedSocial social) {
            return "bad";
        }
    }
}
