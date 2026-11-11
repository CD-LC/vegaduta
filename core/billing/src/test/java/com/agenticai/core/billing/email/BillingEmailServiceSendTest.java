package com.agenticai.core.billing.email;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.security.ImpersonationContext;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import java.math.BigDecimal;
import java.util.List;
import java.util.Properties;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.MailSendException;
import org.springframework.mail.javamail.JavaMailSender;

/**
 * The billing-edition-on path of {@link BillingEmailService}: who gets mail, the subject lines, the
 * branded wrapper, and the degrade-to-no-send behaviour when no admin, no mail sender or a failing
 * SMTP is in play. The edition-off suppression is covered by {@link BillingEmailEditionTest}.
 */
class BillingEmailServiceSendTest {

    private static final UUID TENANT = UUID.fromString("3f0a2b1c-4d5e-6f70-8192-a3b4c5d6e7f8");

    private ObjectProvider<JavaMailSender> provider;
    private JavaMailSender sender;
    private BillingEmailTemplateService templates;
    private JdbcTemplate jdbc;
    private MimeMessage message;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() {
        provider = mock(ObjectProvider.class);
        sender = mock(JavaMailSender.class);
        templates = mock(BillingEmailTemplateService.class);
        jdbc = mock(JdbcTemplate.class);
        message = new MimeMessage(Session.getInstance(new Properties()));
        when(provider.getIfAvailable()).thenReturn(sender);
        when(sender.createMimeMessage()).thenReturn(message);
    }

    private BillingEmailService service(String upgradeUrl) {
        return new BillingEmailService(
                provider,
                templates,
                jdbc,
                "receipt@x",
                "failed@x",
                "dunning@x",
                "welcome@x",
                "https://app.example.com//",
                upgradeUrl,
                true);
    }

    private void adminAndPlan(String email, String plan) {
        when(jdbc.queryForObject(anyString(), eq(String.class), eq(TENANT), eq("TENANT_ADMIN")))
                .thenReturn(email);
        when(jdbc.queryForObject(anyString(), eq(String.class), eq(TENANT))).thenReturn(plan);
    }

    @Test
    void receiptGoesToTheFirstAdminFromTheReceiptAddressWithThePlanInTheSubject() throws Exception {
        adminAndPlan("boss@acme.io", "PRO");
        when(templates.renderReceipt("Acme", "PRO")).thenReturn("<p>thanks</p>");

        service("").sendReceiptEmail(TENANT, "Acme");

        verify(sender).send(message);
        assertThat(message.getSubject()).isEqualTo("Your VegaDūta subscription receipt — PRO");
        assertThat(message.getFrom()[0].toString()).isEqualTo("receipt@x");
        assertThat(message.getAllRecipients()[0].toString()).isEqualTo("boss@acme.io");
    }

    @Test
    void bodyIsWrappedInTheBrandedShellAndTheAppUrlTokenIsResolvedWithoutTrailingSlashes()
            throws Exception {
        adminAndPlan("boss@acme.io", "PRO");
        when(templates.renderWelcome("Acme", "PRO"))
                .thenReturn("<p>hello " + BillingEmailTemplateService.APP_URL_TOKEN + "/x</p>");

        service("").sendWelcomeEmail(TENANT, "Acme");

        String html = (String) message.getContent();
        assertThat(html).contains("<!DOCTYPE html>").contains("hello https://app.example.com/x");
        assertThat(html).doesNotContain(BillingEmailTemplateService.APP_URL_TOKEN);
        assertThat(message.getSubject()).isEqualTo("Welcome to VegaDūta PRO!");
    }

    @Test
    void aFullHtmlDocumentIsNotWrappedAgain() throws Exception {
        adminAndPlan("boss@acme.io", "PRO");
        when(templates.renderDunning("Acme", "PRO", 2))
                .thenReturn("<html><body>already complete</body></html>");

        service("").sendDunningEmail(TENANT, "Acme", 2);

        assertThat((String) message.getContent())
                .isEqualTo("<html><body>already complete</body></html>");
        assertThat(message.getFrom()[0].toString()).isEqualTo("dunning@x");
    }

    @Test
    void paymentFailedAndPromoUseTheirOwnSubjectsAndSenders() throws Exception {
        adminAndPlan("boss@acme.io", "PRO");
        when(templates.renderPaymentFailed("Acme", "PRO", "declined")).thenReturn("<p>f</p>");
        when(templates.renderUnlimitedPromo("Acme", "1 Jan")).thenReturn("<p>p</p>");

        BillingEmailService svc = service("");
        svc.sendPaymentFailedEmail(TENANT, "Acme", "declined");
        assertThat(message.getSubject()).isEqualTo("Your VegaDūta payment could not be processed");
        assertThat(message.getFrom()[0].toString()).isEqualTo("failed@x");

        svc.sendUnlimitedPromoEmail(TENANT, "Acme", "1 Jan");
        assertThat(message.getSubject()).contains("Unlimited access for 2 weeks");
        assertThat(message.getFrom()[0].toString()).isEqualTo("welcome@x");
    }

    @Test
    void noAdminMeansNothingIsRenderedOrSent() {
        when(jdbc.queryForObject(anyString(), eq(String.class), eq(TENANT), eq("TENANT_ADMIN")))
                .thenThrow(new org.springframework.dao.EmptyResultDataAccessException(1));
        BillingEmailService svc = service("");

        svc.sendReceiptEmail(TENANT, "Acme");
        svc.sendWelcomeEmail(TENANT, "Acme");
        svc.sendPaymentFailedEmail(TENANT, "Acme", "x");
        svc.sendDunningEmail(TENANT, "Acme", 1);
        svc.sendUnlimitedPromoEmail(TENANT, "Acme", "d");
        assertThat(svc.sendCompGrantReminderEmail(TENANT, "Acme", "d", 3)).isFalse();
        assertThat(svc.sendCompGrantEndedEmail(TENANT, "Acme")).isFalse();

        verify(sender, never()).send(any(MimeMessage.class));
    }

    @Test
    void planLookupFailureFallsBackToPremium() throws Exception {
        when(jdbc.queryForObject(anyString(), eq(String.class), eq(TENANT), eq("TENANT_ADMIN")))
                .thenReturn("boss@acme.io");
        when(jdbc.queryForObject(anyString(), eq(String.class), eq(TENANT)))
                .thenThrow(new IllegalStateException("db down"));
        when(templates.renderReceipt("Acme", "Premium")).thenReturn("<p>r</p>");

        service("").sendReceiptEmail(TENANT, "Acme");

        assertThat(message.getSubject()).endsWith("— Premium");
    }

    @Test
    void compGrantReminderSaysTomorrowOnTheLastDayAndPassesTheConfiguredUpgradeUrl()
            throws Exception {
        adminAndPlan("boss@acme.io", "PRO");
        when(templates.renderCompGrantReminder("Acme", "1 Jan", 1, "https://pay/up"))
                .thenReturn("<p>r</p>");
        when(templates.renderCompGrantReminder("Acme", "1 Jan", 5, "https://pay/up"))
                .thenReturn("<p>r</p>");
        BillingEmailService svc = service("https://pay/up");

        assertThat(svc.sendCompGrantReminderEmail(TENANT, "Acme", "1 Jan", 1)).isTrue();
        assertThat(message.getSubject()).isEqualTo("Your free full access ends tomorrow");
        assertThat(svc.sendCompGrantReminderEmail(TENANT, "Acme", "1 Jan", 5)).isTrue();
        assertThat(message.getSubject()).isEqualTo("Your free full access ends in 5 days");
    }

    @Test
    void upgradeUrlDefaultsToTheBillingPageOfThePublicUrl() {
        adminAndPlan("boss@acme.io", "PRO");
        when(templates.renderCompGrantEnded(eq("Acme"), anyString())).thenReturn("<p>e</p>");

        assertThat(service("").sendCompGrantEndedEmail(TENANT, "Acme")).isTrue();

        ArgumentCaptor<String> url = ArgumentCaptor.forClass(String.class);
        verify(templates).renderCompGrantEnded(eq("Acme"), url.capture());
        assertThat(url.getValue()).isEqualTo("https://app.example.com/billing");
    }

    @Test
    void spendCapAlertMailsEveryAdminAndCountsTheDeliveries() throws Exception {
        when(jdbc.queryForList(anyString(), eq(String.class), eq(TENANT), eq("TENANT_ADMIN")))
                .thenReturn(List.of("a@acme.io", "b@acme.io"));
        when(templates.renderSpendCapAlert(
                        "Acme", 100, new BigDecimal("50"), new BigDecimal("50"), "1 Feb"))
                .thenReturn("<p>cap</p>");

        int sent =
                service("")
                        .sendSpendCapAlertEmail(
                                TENANT,
                                "Acme",
                                100,
                                new BigDecimal("50"),
                                new BigDecimal("50"),
                                "1 Feb");

        assertThat(sent).isEqualTo(2);
        assertThat(message.getSubject()).contains("reached its monthly AI spend limit");
    }

    @Test
    void spendCapAlertAtEightyPercentUsesTheWarningSubject() throws Exception {
        when(jdbc.queryForList(anyString(), eq(String.class), eq(TENANT), eq("TENANT_ADMIN")))
                .thenReturn(List.of("a@acme.io"));
        when(templates.renderSpendCapAlert(any(), eq(80), any(), any(), any()))
                .thenReturn("<p>w</p>");

        service("")
                .sendSpendCapAlertEmail(
                        TENANT, "Acme", 80, BigDecimal.TEN, BigDecimal.valueOf(12), "1 Feb");

        assertThat(message.getSubject()).contains("used 80%");
    }

    @Test
    void spendCapAlertWithNoAdminsOrAFailingLookupSendsNothing() {
        when(jdbc.queryForList(anyString(), eq(String.class), eq(TENANT), eq("TENANT_ADMIN")))
                .thenThrow(new IllegalStateException("db down"));

        int sent =
                service("")
                        .sendSpendCapAlertEmail(
                                TENANT, "Acme", 80, BigDecimal.ONE, BigDecimal.TEN, "1 Feb");

        assertThat(sent).isZero();
        verify(sender, never()).send(any(MimeMessage.class));
    }

    @Test
    void missingMailSenderIsASilentNoSend() {
        adminAndPlan("boss@acme.io", "PRO");
        when(provider.getIfAvailable()).thenReturn(null);
        when(templates.renderCompGrantEnded(eq("Acme"), anyString())).thenReturn("<p>e</p>");

        assertThat(service("").sendCompGrantEndedEmail(TENANT, "Acme")).isFalse();
    }

    @Test
    void smtpFailureIsSwallowedAndReportedAsNotSent() {
        adminAndPlan("boss@acme.io", "PRO");
        when(templates.renderCompGrantEnded(eq("Acme"), anyString())).thenReturn("<p>e</p>");
        doThrow(new MailSendException("smtp down")).when(sender).send(any(MimeMessage.class));

        assertThat(service("").sendCompGrantEndedEmail(TENANT, "Acme")).isFalse();
    }

    @Test
    void sendingIsRefusedWhileAnAdminIsViewingAsTheTenant() {
        adminAndPlan("boss@acme.io", "PRO");
        when(templates.renderReceipt("Acme", "PRO")).thenReturn("<p>r</p>");
        BillingEmailService svc = service("");

        ImpersonationContext.set(
                new ImpersonationContext.Session(
                        UUID.randomUUID(), "admin-sub", "admin@x", "tenant_acme", null));
        try {
            assertThatThrownBy(() -> svc.sendReceiptEmail(TENANT, "Acme"))
                    .isInstanceOf(RuntimeException.class);
        } finally {
            ImpersonationContext.clear();
        }
        verify(sender, never()).send(any(MimeMessage.class));
    }
}
