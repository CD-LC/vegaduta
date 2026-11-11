package com.agenticai.core.billing.gateway;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/**
 * {@link StripeGatewayClient}'s outbound calls against a mock HTTP server: the form bodies sent to
 * Stripe, the bearer authentication, and how empty or failing responses surface. Signature and
 * webhook parsing live in {@link StripeGatewayClientTest}.
 */
class StripeGatewayClientHttpTest {

    private MockRestServiceServer server;
    private StripeGatewayClient stripe;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        stripe =
                new StripeGatewayClient(
                        builder,
                        "https://api.stripe.test",
                        "sk_test_x",
                        "pk_test_x",
                        "whsec_x",
                        "price_premium",
                        "price_unlimited");
    }

    @Test
    void subscriptionCreationMakesACustomerThenAnIncompleteSubscriptionWithTheTrial() {
        server.expect(requestTo("https://api.stripe.test/v1/customers"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Bearer sk_test_x"))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("name=Ann")))
                .andExpect(
                        content()
                                .string(
                                        org.hamcrest.Matchers.containsString(
                                                "metadata%5Bagentic_ai_tenant_id%5D=t1")))
                .andRespond(withSuccess("{\"id\":\"cus_1\"}", MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://api.stripe.test/v1/subscriptions"))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("customer=cus_1")))
                .andExpect(
                        content()
                                .string(
                                        org.hamcrest.Matchers.containsString(
                                                "payment_behavior=default_incomplete")))
                .andExpect(
                        content()
                                .string(
                                        org.hamcrest.Matchers.containsString(
                                                "trial_period_days=14")))
                .andRespond(
                        withSuccess(
                                "{\"id\":\"sub_1\",\"status\":\"incomplete\","
                                        + "\"latest_invoice\":{\"hosted_invoice_url\":\"https://inv\"}}",
                                MediaType.APPLICATION_JSON));

        GatewaySubscription result =
                stripe.createSubscriptionWithTrial("t1", "premium", 14, "a@b.co", "Ann");

        assertEquals("sub_1", result.id());
        assertEquals("https://inv", result.checkoutUrl());
        assertEquals("incomplete", result.status());
        server.verify();
    }

    @Test
    void noTrialDaysOmitsTheTrialField() {
        server.expect(requestTo("https://api.stripe.test/v1/customers"))
                .andRespond(withSuccess("{\"id\":\"cus_1\"}", MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://api.stripe.test/v1/subscriptions"))
                .andExpect(
                        content()
                                .string(
                                        org.hamcrest.Matchers.not(
                                                org.hamcrest.Matchers.containsString(
                                                        "trial_period_days"))))
                .andRespond(withSuccess("{\"id\":\"sub_2\"}", MediaType.APPLICATION_JSON));

        GatewaySubscription result =
                stripe.createSubscriptionWithTrial("t1", "UNLIMITED", 0, null, null);

        assertEquals("sub_2", result.id());
        assertNull(result.checkoutUrl());
        assertEquals("incomplete", result.status());
    }

    @Test
    void aCustomerResponseWithoutAnIdFailsTheCreation() {
        server.expect(requestTo("https://api.stripe.test/v1/customers"))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

        PaymentGatewayException e =
                assertThrows(
                        PaymentGatewayException.class,
                        () -> stripe.createSubscriptionWithTrial("t", "PREMIUM", 0, null, null));
        assertTrue(e.getMessage().contains("no id"));
    }

    @Test
    void aSubscriptionResponseWithoutAnIdFailsTheCreation() {
        server.expect(requestTo("https://api.stripe.test/v1/customers"))
                .andRespond(withSuccess("{\"id\":\"cus_1\"}", MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://api.stripe.test/v1/subscriptions"))
                .andRespond(withSuccess("{\"status\":\"incomplete\"}", MediaType.APPLICATION_JSON));

        assertThrows(
                PaymentGatewayException.class,
                () -> stripe.createSubscriptionWithTrial("t", "PREMIUM", 0, null, null));
    }

    @Test
    void anHttpErrorFromStripeIsWrappedAsAGatewayException() {
        server.expect(requestTo("https://api.stripe.test/v1/customers"))
                .andRespond(withServerError());

        PaymentGatewayException e =
                assertThrows(
                        PaymentGatewayException.class,
                        () -> stripe.createSubscriptionWithTrial("t", "PREMIUM", 0, null, null));
        assertTrue(e.getMessage().startsWith("Could not create Stripe subscription"));
    }

    @Test
    void overageIsPostedAsAnInvoiceItemWithALowercasedCurrency() {
        server.expect(requestTo("https://api.stripe.test/v1/invoiceitems"))
                .andExpect(
                        content().string(org.hamcrest.Matchers.containsString("unit_amount=2500")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("currency=usd")))
                .andExpect(
                        content()
                                .string(org.hamcrest.Matchers.containsString("subscription=sub_1")))
                .andRespond(withSuccess("{\"id\":\"ii_1\"}", MediaType.APPLICATION_JSON));

        stripe.createOverageAddon("sub_1", "Extra", 2500, "USD");

        server.verify();
    }

    @Test
    void overageDefaultsToInrWhenNoCurrencyIsGiven() {
        server.expect(requestTo("https://api.stripe.test/v1/invoiceitems"))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("currency=inr")))
                .andRespond(withSuccess("{\"id\":\"ii_1\"}", MediaType.APPLICATION_JSON));

        stripe.createOverageAddon("sub_1", "Extra", 100, null);

        server.verify();
    }

    @Test
    void overageHttpFailureIsWrapped() {
        server.expect(requestTo("https://api.stripe.test/v1/invoiceitems"))
                .andRespond(withServerError());

        assertThrows(
                PaymentGatewayException.class,
                () -> stripe.createOverageAddon("sub_1", "Extra", 100, "inr"));
    }

    @Test
    void cancelAtCycleEndPostsThePeriodEndFlagAndReturnsTheStatus() {
        server.expect(requestTo("https://api.stripe.test/v1/subscriptions/sub_1"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(
                        content()
                                .string(
                                        org.hamcrest.Matchers.containsString(
                                                "cancel_at_period_end=true")))
                .andRespond(withSuccess("{\"status\":\"active\"}", MediaType.APPLICATION_JSON));

        assertEquals("active", stripe.cancelSubscription("sub_1", true));
    }

    @Test
    void immediateCancellationDeletesTheSubscription() {
        server.expect(requestTo("https://api.stripe.test/v1/subscriptions/sub_1"))
                .andExpect(method(HttpMethod.DELETE))
                .andExpect(header("Authorization", "Bearer sk_test_x"))
                .andRespond(withSuccess("{\"status\":\"canceled\"}", MediaType.APPLICATION_JSON));

        assertEquals("canceled", stripe.cancelSubscription("sub_1", false));
    }

    @Test
    void immediateCancellationWithAnEmptyBodyStillReportsCanceled() {
        server.expect(requestTo("https://api.stripe.test/v1/subscriptions/sub_1"))
                .andRespond(withSuccess());

        assertEquals("canceled", stripe.cancelSubscription("sub_1", false));
    }

    @Test
    void anEmptyPostResponseIsAGatewayError() {
        server.expect(requestTo("https://api.stripe.test/v1/subscriptions/sub_1"))
                .andRespond(withSuccess());

        assertThrows(PaymentGatewayException.class, () -> stripe.cancelSubscription("sub_1", true));
    }

    @Test
    void cancelHttpFailureIsWrapped() {
        server.expect(requestTo("https://api.stripe.test/v1/subscriptions/sub_1"))
                .andRespond(withServerError());

        assertThrows(
                PaymentGatewayException.class, () -> stripe.cancelSubscription("sub_1", false));
    }
}
