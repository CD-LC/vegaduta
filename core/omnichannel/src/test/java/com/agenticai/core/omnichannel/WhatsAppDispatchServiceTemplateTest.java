package com.agenticai.core.omnichannel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.agenticai.core.omnichannel.meta.MetaGraphApiClient;
import com.agenticai.core.omnichannel.meta.MetaWhatsAppCredentialService;
import com.agenticai.core.omnichannel.meta.MetaWhatsAppProvider;
import com.agenticai.core.omnichannel.twilio.TwilioAccountProbe;
import com.agenticai.core.omnichannel.whatsapp.WhatsAppSendBlockedException;
import com.agenticai.core.omnichannel.whatsapp.WhatsAppSendPolicy;
import com.agenticai.core.omnichannel.twilio.TwilioCredentialService;
import com.agenticai.core.omnichannel.twilio.TwilioWhatsAppProvider;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Provider order for a template send: Meta sends the template; any other connected provider sends
 * the fallback text; with neither there is nothing to send.
 */
class WhatsAppDispatchServiceTemplateTest {

    private MetaWhatsAppCredentialService meta;
    private MetaGraphApiClient graph;
    private TwilioCredentialService twilio;
    private WhatsAppDispatchService dispatch;
    private WhatsAppSendPolicy guard;

    @BeforeEach
    void setUp() {
        meta = mock(MetaWhatsAppCredentialService.class);
        graph = mock(MetaGraphApiClient.class);
        twilio = mock(TwilioCredentialService.class);
        guard = mock(WhatsAppSendPolicy.class);
        // Deliberately out of precedence order: the dispatcher sorts.
        dispatch =
                new WhatsAppDispatchService(
                        List.of(
                                new TwilioWhatsAppProvider(
                                        twilio, mock(TwilioAccountProbe.class), ""),
                                new MetaWhatsAppProvider(meta, graph)),
                        guard);
    }

    private void metaConnected(String wabaId) {
        when(meta.isConfigured()).thenReturn(true);
        when(meta.resolveActive())
                .thenReturn(
                        new MetaWhatsAppCredentialService.DecryptedCredential(
                                "pn-1", "token-1", "app-secret", wabaId));
    }

    @Test
    void metaSendsTheTemplateAndNeverTheFallbackText() {
        metaConnected("waba-1");
        when(graph.sendTemplateMessage(
                        "pn-1", "token-1", "+1555", "invoice_reminder", "en_US", List.of("Asha")))
                .thenReturn("Sent template.");

        assertEquals(
                "Sent template.",
                dispatch.sendTemplate(
                        "+1555", "invoice_reminder", "en_US", List.of("Asha"), "plain fallback"));

        verify(graph, never()).sendTextMessage(anyString(), anyString(), anyString(), anyString());
        verifyNoInteractions(twilio);
    }

    @Test
    void withoutMetaTheFallbackTextIsNotSentAsATemplate() {
        when(meta.isConfigured()).thenReturn(false);
        when(twilio.isActive()).thenReturn(false);

        // No Meta connection: the template is never attempted, and with no other provider
        // connected the plain-text fallback fails with a configuration error, not a template send.
        assertThrows(
                ChannelConfigurationException.class,
                () ->
                        dispatch.sendTemplate(
                                "+1555",
                                "invoice_reminder",
                                "en_US",
                                List.of("Asha"),
                                "plain fallback"));

        verify(graph, never()).sendTemplateMessage(any(), any(), any(), any(), any(), anyList());
    }

    @Test
    void withoutMetaAndWithoutFallbackTextThereIsNothingToSend() {
        when(meta.isConfigured()).thenReturn(false);
        for (String fallback : new String[] {null, "", "   "}) {
            ChannelConfigurationException e =
                    assertThrows(
                            ChannelConfigurationException.class,
                            () ->
                                    dispatch.sendTemplate(
                                            "+1555",
                                            "invoice_reminder",
                                            "en_US",
                                            List.of(),
                                            fallback));
            assertTrue(e.getMessage().contains("Meta WhatsApp Cloud API"), e.getMessage());
        }
        verifyNoInteractions(graph);
    }

    @Test
    void templatesAreListedOnlyForAMetaConnectionWithAWabaId() {
        when(meta.isConfigured()).thenReturn(false);
        assertTrue(dispatch.listApprovedTemplates().isEmpty());

        metaConnected(" ");
        assertTrue(
                dispatch.listApprovedTemplates().isEmpty(), "no WABA id, nothing to ask Meta for");
        verifyNoInteractions(graph);

        metaConnected("waba-1");
        when(graph.listMessageTemplates("waba-1", "token-1"))
                .thenReturn(
                        List.of(
                                new MetaGraphApiClient.MessageTemplate(
                                        "invoice_reminder", "en_US", "UTILITY", "Hi {{1}}", 1)));
        assertEquals(1, dispatch.listApprovedTemplates().size());
    }

    @Test
    void anyConnectedProviderCountsAsConnected() {
        assertFalse(dispatch.isAnyProviderConnected());
        when(twilio.isActive()).thenReturn(true);
        assertTrue(dispatch.isAnyProviderConnected());
    }

    @Test
    void aBlockedRecipientNeverReachesMeta() {
        metaConnected("waba-1");
        org.mockito.Mockito.doThrow(new WhatsAppSendBlockedException("no opt-in"))
                .when(guard)
                .assertMaySendTemplate(anyString(), any());

        assertThrows(
                WhatsAppSendBlockedException.class,
                () -> dispatch.sendTemplate("+1555", "promo", "en_US", List.of(), null));

        verifyNoInteractions(graph);
    }

    @Test
    void aMetaRejectionIsRecordedForBackoffAndRethrown() {
        metaConnected("waba-1");
        when(graph.sendTemplateMessage(any(), any(), any(), any(), any(), anyList()))
                .thenThrow(new WhatsAppDeliveryRejectedException("limit", 131049));

        assertThrows(
                WhatsAppDeliveryRejectedException.class,
                () -> dispatch.sendTemplate("+1555", "promo", "en_US", List.of(), null));

        verify(guard).recordRejection("+1555", 131049);
    }

    @Test
    void theMarketingLookupTreatsAnUnknownTemplateAsMarketing() {
        metaConnected("waba-1");
        when(graph.listMessageTemplates("waba-1", "token-1"))
                .thenReturn(
                        List.of(
                                new MetaGraphApiClient.MessageTemplate(
                                        "invoice_reminder", "en_US", "UTILITY", "Hi", 0)));
        when(graph.sendTemplateMessage(any(), any(), any(), any(), any(), anyList()))
                .thenReturn("ok");
        java.util.concurrent.atomic.AtomicReference<java.util.function.BooleanSupplier> probe =
                new java.util.concurrent.atomic.AtomicReference<>();
        org.mockito.Mockito.doAnswer(
                        inv -> {
                            probe.set(inv.getArgument(1));
                            return null;
                        })
                .when(guard)
                .assertMaySendTemplate(anyString(), any());

        dispatch.sendTemplate("+1555", "invoice_reminder", "en_US", List.of(), null);
        assertFalse(probe.get().getAsBoolean(), "an approved UTILITY template is not marketing");

        dispatch.sendTemplate("+1555", "unlisted", "en_US", List.of(), null);
        assertTrue(probe.get().getAsBoolean(), "unknown category falls to the stricter reading");
    }
}
