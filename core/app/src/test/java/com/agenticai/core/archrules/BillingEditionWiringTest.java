package com.agenticai.core.archrules;

import static org.assertj.core.api.Assertions.assertThat;

import com.agenticai.core.billing.gateway.BillingWebhookController;
import com.agenticai.core.billing.gateway.PaymentCheckoutController;
import com.agenticai.core.billing.gateway.RazorpayCheckoutController;
import com.agenticai.core.billing.invoice.InvoiceController;
import com.agenticai.core.common.budget.BudgetTopUpController;
import com.agenticai.core.tenancy.CompGrantExpiryReverter;
import com.agenticai.core.tenancy.CompGrantReminderJob;
import com.agenticai.core.tenancy.FreeModeService;
import com.agenticai.core.tenancy.RazorpayWebhookController;
import com.agenticai.core.tenancy.StalePendingUpgradeCleanup;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotatedBeanDefinitionReader;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

/**
 * The billing edition ({@code app.edition.billing-enabled}) removes only LEAF beans: payment webhooks, checkout
 * and invoice controllers, plan-lifecycle schedulers. Every bean other modules inject (plan limits, entitlement
 * ports, FreeModeService, gateway registry) stays registered in both editions, so the context wires either way.
 *
 * <p>This evaluates the real {@code @Conditional} on the real classes against both property values without
 * instantiating anything (registration only evaluates conditions), so it needs no database or Redis. The
 * bytecode wiring guards ({@link UniqueBeanNamesTest}, {@link InjectableConstructorTest}) cover the same
 * classes in both editions, since they are condition-blind and see every class.
 */
class BillingEditionWiringTest {

    private static final List<Class<?>> LEAF_BEANS = List.of(
        BillingWebhookController.class,
        PaymentCheckoutController.class,
        RazorpayCheckoutController.class,
        InvoiceController.class,
        RazorpayWebhookController.class,
        BudgetTopUpController.class,
        CompGrantReminderJob.class,
        CompGrantExpiryReverter.class,
        StalePendingUpgradeCleanup.class);

    private static GenericApplicationContext register(Map<String, Object> props, Class<?>... classes) {
        GenericApplicationContext ctx = new GenericApplicationContext();
        StandardEnvironment env = new StandardEnvironment();
        env.setActiveProfiles("security"); // most leaf beans are @Profile("security")
        env.getPropertySources().addFirst(new MapPropertySource("test", props));
        ctx.setEnvironment(env);
        new AnnotatedBeanDefinitionReader(ctx).register(classes);
        return ctx;
    }

    private static int registered(GenericApplicationContext ctx) {
        // The reader also registers Spring's own annotation processors; count only ours.
        return (int) java.util.Arrays.stream(ctx.getBeanDefinitionNames())
            .map(n -> ctx.getBeanDefinition(n).getBeanClassName())
            .filter(c -> c != null && c.startsWith("com.agenticai."))
            .count();
    }

    @Test
    void defaultKeepsEveryLeafBean() {
        try (GenericApplicationContext ctx = register(Map.of(), LEAF_BEANS.toArray(Class<?>[]::new))) {
            assertThat(registered(ctx)).isEqualTo(LEAF_BEANS.size());
        }
    }

    @Test
    void explicitTrueKeepsEveryLeafBean() {
        try (GenericApplicationContext ctx = register(
                Map.of("app.edition.billing-enabled", "true"), LEAF_BEANS.toArray(Class<?>[]::new))) {
            assertThat(registered(ctx)).isEqualTo(LEAF_BEANS.size());
        }
    }

    @Test
    void falseDropsEveryLeafBean() {
        try (GenericApplicationContext ctx = register(
                Map.of("app.edition.billing-enabled", "false"), LEAF_BEANS.toArray(Class<?>[]::new))) {
            assertThat(registered(ctx)).isZero();
        }
    }

    /** The plan ports read the same property as a boolean through {@code @Value}; both halves must agree. */
    @Test
    void everyBooleanSpellingTheValueInjectionAcceptsSwitchesTheBeansTheSameWay() {
        for (String off : List.of("false", "FALSE", "0", "off", "no")) {
            try (GenericApplicationContext ctx = register(
                    Map.of("app.edition.billing-enabled", off), LEAF_BEANS.toArray(Class<?>[]::new))) {
                assertThat(registered(ctx)).as("billing-enabled=%s", off).isZero();
            }
        }
        for (String on : List.of("true", "TRUE", "1", "on", "yes")) {
            try (GenericApplicationContext ctx = register(
                    Map.of("app.edition.billing-enabled", on), LEAF_BEANS.toArray(Class<?>[]::new))) {
                assertThat(registered(ctx)).as("billing-enabled=%s", on).isEqualTo(LEAF_BEANS.size());
            }
        }
    }

    @Test
    void injectedPortsAreRegisteredInBothEditions() {
        for (String value : List.of("true", "false")) {
            try (GenericApplicationContext ctx = register(Map.of("app.edition.billing-enabled", value), FreeModeService.class)) {
                assertThat(registered(ctx)).as("FreeModeService with billing-enabled=%s", value).isEqualTo(1);
            }
        }
    }
}
