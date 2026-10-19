package com.agenticai.core.billing.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Zero test coverage existed for {@link PaymentGatewayRegistry} before this - the class every
 * multi-gateway-aware caller (starting with {@code TenantUpgradeService}) now depends on instead
 * of the {@link PaymentGatewayClient} interface directly, precisely BECAUSE that interface stopped
 * being safely injectable the moment a second implementation ({@link PhonePeGatewayClient})
 * existed. A bug here (wrong default, a name collision silently picking the wrong gateway) would
 * misroute real money, so this needed direct coverage before anything depended on it.
 */
class PaymentGatewayRegistryTest {

    private static PaymentGatewayClient gatewayNamed(String name) {
        PaymentGatewayClient client = mock(PaymentGatewayClient.class);
        when(client.gatewayName()).thenReturn(name);
        return client;
    }

    @Test
    void resolvesToTheConfiguredDefaultWhenGivenNull() {
        PaymentGatewayClient razorpay = gatewayNamed("razorpay");
        PaymentGatewayClient phonepe = gatewayNamed("phonepe");
        PaymentGatewayRegistry registry = new PaymentGatewayRegistry(List.of(razorpay, phonepe), "razorpay");

        assertThat(registry.resolve(null)).isSameAs(razorpay);
    }

    @Test
    void resolvesToTheConfiguredDefaultWhenGivenABlankName() {
        PaymentGatewayClient razorpay = gatewayNamed("razorpay");
        PaymentGatewayRegistry registry = new PaymentGatewayRegistry(List.of(razorpay), "razorpay");

        assertThat(registry.resolve("   ")).isSameAs(razorpay);
    }

    @Test
    void resolvesToAnExplicitlyNamedNonDefaultGateway() {
        PaymentGatewayClient razorpay = gatewayNamed("razorpay");
        PaymentGatewayClient phonepe = gatewayNamed("phonepe");
        PaymentGatewayRegistry registry = new PaymentGatewayRegistry(List.of(razorpay, phonepe), "razorpay");

        assertThat(registry.resolve("phonepe")).isSameAs(phonepe);
    }

    @Test
    void anUnknownGatewayNameThrowsRatherThanSilentlyFallingBackToTheDefault() {
        PaymentGatewayClient razorpay = gatewayNamed("razorpay");
        PaymentGatewayRegistry registry = new PaymentGatewayRegistry(List.of(razorpay), "razorpay");

        assertThatThrownBy(() -> registry.resolve("stripe"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("stripe");
    }

    @Test
    void anEmptyGatewayListStillConstructsButResolvingAnythingThrows() {
        PaymentGatewayRegistry registry = new PaymentGatewayRegistry(List.of(), "razorpay");

        assertThatThrownBy(() -> registry.resolve(null)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void availableGatewayNamesReflectsEveryRegisteredGateway() {
        PaymentGatewayClient razorpay = gatewayNamed("razorpay");
        PaymentGatewayClient phonepe = gatewayNamed("phonepe");
        PaymentGatewayRegistry registry = new PaymentGatewayRegistry(List.of(razorpay, phonepe), "razorpay");

        assertThat(registry.availableGatewayNames()).containsExactlyInAnyOrder("razorpay", "phonepe");
    }
}
