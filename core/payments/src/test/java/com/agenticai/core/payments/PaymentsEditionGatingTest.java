package com.agenticai.core.payments;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.context.annotation.AnnotatedBeanDefinitionReader;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.context.annotation.ConfigurationClassPostProcessor;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

/**
 * The BILLING_PAYMENTS edition feature removes the whole closed set from the component scan: every
 * stereotyped class of the payments module and of the billing gateway / invoice / commerce
 * packages, and the payments Spring Data repositories (Spring Data's repository scanner honours
 * {@code @Conditional} like the plain component scan). Scans the real classpath with the real
 * conditions, no context and no database.
 */
class PaymentsEditionGatingTest {

    private static final Map<String, Object> PERSONAL =
            Map.of("app.edition.tier", "personal", "app.edition.billing-enabled", "false");

    private static StandardEnvironment env(Map<String, Object> props) {
        StandardEnvironment env = new StandardEnvironment();
        // RazorpayAdapter and most billing leaf beans are @Profile("security"): scan as the real
        // app does.
        env.setActiveProfiles("security");
        env.getPropertySources().addFirst(new MapPropertySource("test", props));
        return env;
    }

    private static Set<String> components(Map<String, Object> props, String basePackage) {
        ClassPathScanningCandidateComponentProvider scanner =
                new ClassPathScanningCandidateComponentProvider(true);
        scanner.setEnvironment(env(props));
        return scanner.findCandidateComponents(basePackage).stream()
                .map(BeanDefinition::getBeanClassName)
                .filter(name -> !name.contains("Test")) // test-scope @Configuration classes
                .collect(Collectors.toSet());
    }

    /**
     * Names of the Spring Data repository beans an {@code @EnableJpaRepositories} scan registers.
     */
    private static Set<String> repositories(Map<String, Object> props) {
        DefaultListableBeanFactory registry = new DefaultListableBeanFactory();
        StandardEnvironment env = env(props);
        new AnnotatedBeanDefinitionReader(registry, env).register(PaymentsRepositories.class);
        ConfigurationClassPostProcessor processor = new ConfigurationClassPostProcessor();
        processor.setEnvironment(env);
        processor.setResourceLoader(new DefaultResourceLoader());
        processor.setBeanClassLoader(PaymentsEditionGatingTest.class.getClassLoader());
        processor.postProcessBeanDefinitionRegistry(registry);
        return Arrays.stream(registry.getBeanDefinitionNames())
                .filter(n -> n.endsWith("Repository"))
                .collect(Collectors.toSet());
    }

    @EnableJpaRepositories(basePackages = "com.agenticai.core.payments")
    static class PaymentsRepositories {}

    @Test
    void personalScansNoPaymentsComponentAndEveryEditionScansThemAll() {
        Set<String> all = components(Map.of(), "com.agenticai.core.payments");

        assertThat(all)
                .contains(
                        "com.agenticai.core.payments.PaymentController",
                        "com.agenticai.core.payments.PaymentReconciliationJob",
                        "com.agenticai.core.payments.wallet.WalletController",
                        "com.agenticai.core.payments.agenttools.PaymentMandateToolSource",
                        "com.agenticai.core.payments.agenttools.PaymentsAgentWalletLedger");
        assertThat(components(PERSONAL, "com.agenticai.core.payments")).isEmpty();
        assertThat(components(Map.of("app.edition.tier", "lite"), "com.agenticai.core.payments"))
                .isEqualTo(all);
        assertThat(components(Map.of("app.edition.tier", "full"), "com.agenticai.core.payments"))
                .isEqualTo(all);
    }

    @Test
    void personalWithBillingSwitchedBackOnKeepsThePaymentsComponents() {
        Map<String, Object> props =
                Map.of("app.edition.tier", "personal", "app.edition.billing-enabled", "true");

        assertThat(components(props, "com.agenticai.core.payments"))
                .isEqualTo(components(Map.of(), "com.agenticai.core.payments"));
    }

    @Test
    void personalScansNoBillingGatewayInvoiceOrCommerceComponentExceptTheConfigurations() {
        for (String pkg :
                List.of(
                        "com.agenticai.core.billing.gateway",
                        "com.agenticai.core.billing.invoice",
                        "com.agenticai.core.billing.commerce")) {
            assertThat(components(Map.of(), pkg)).as("default %s", pkg).isNotEmpty();
        }
        // The two @Configuration classes only enable properties; they stay so the properties still
        // bind.
        assertThat(components(PERSONAL, "com.agenticai.core.billing.gateway"))
                .containsExactly("com.agenticai.core.billing.gateway.RazorpayBillingConfiguration");
        assertThat(components(PERSONAL, "com.agenticai.core.billing.invoice"))
                .containsExactly("com.agenticai.core.billing.invoice.InvoiceConfiguration");
        assertThat(components(PERSONAL, "com.agenticai.core.billing.commerce")).isEmpty();
    }

    @Test
    void personalSkipsThePaymentsSpringDataRepositories() {
        assertThat(repositories(Map.of()))
                .contains(
                        "paymentTransactionRepository",
                        "agentWalletRepository",
                        "commerceMandateRepository");
        assertThat(repositories(PERSONAL)).isEmpty();
    }
}
