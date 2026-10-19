package com.agenticai.core.billing.email;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.javamail.JavaMailSender;

/** {@code app.edition.billing-enabled=false}: no billing mail is composed, looked up or sent. */
class BillingEmailEditionTest {

    @SuppressWarnings("unchecked")
    private final ObjectProvider<JavaMailSender> mailSender = mock(ObjectProvider.class);
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final BillingEmailTemplateService templates = mock(BillingEmailTemplateService.class);

    private BillingEmailService service(boolean billingEdition) {
        return new BillingEmailService(
                mailSender, templates, jdbc, "a@x", "b@x", "c@x", "d@x", "http://app", "", billingEdition);
    }

    @Test
    void everyBillingMailIsSuppressedWhenTheEditionIsOff() {
        BillingEmailService off = service(false);
        UUID tenant = UUID.randomUUID();

        off.sendReceiptEmail(tenant, "T");
        off.sendWelcomeEmail(tenant, "T");
        off.sendPaymentFailedEmail(tenant, "T", "declined");
        off.sendDunningEmail(tenant, "T", 1);
        off.sendUnlimitedPromoEmail(tenant, "T", "1 Jan 2030");

        assertThat(off.sendCompGrantReminderEmail(tenant, "T", "1 Jan 2030", 3)).isFalse();
        assertThat(off.sendCompGrantEndedEmail(tenant, "T")).isFalse();
        verifyNoInteractions(mailSender, jdbc, templates);
    }
}
