package com.agenticai.core.omnichannel.meta;

import com.agenticai.core.omnichannel.status.ChannelNotConnectedException;
import com.agenticai.core.omnichannel.whatsapp.WhatsAppProvider;
import com.agenticai.core.omnichannel.whatsapp.WhatsAppTemplate;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * WhatsApp through Meta's Cloud API - the preferred provider (direct-to-Meta is lower latency and
 * has no BSP markup), and the only one with approved templates.
 */
@Component
public class MetaWhatsAppProvider implements WhatsAppProvider {

    private final MetaWhatsAppCredentialService credentialService;
    private final MetaGraphApiClient apiClient;

    public MetaWhatsAppProvider(
            MetaWhatsAppCredentialService credentialService, MetaGraphApiClient apiClient) {
        this.credentialService = credentialService;
        this.apiClient = apiClient;
    }

    @Override
    public int precedence() {
        return 10;
    }

    @Override
    public String label() {
        return "Meta Cloud API";
    }

    @Override
    public boolean isActive() {
        return credentialService.isConfigured();
    }

    @Override
    public String sendText(String to, String body) {
        MetaWhatsAppCredentialService.DecryptedCredential credential =
                credentialService.resolveActive();
        return apiClient.sendTextMessage(
                credential.phoneNumberId(), credential.accessToken(), to, body);
    }

    @Override
    public boolean supportsTemplates() {
        return true;
    }

    @Override
    public String sendTemplate(
            String to, String templateName, String languageCode, List<String> bodyParams) {
        MetaWhatsAppCredentialService.DecryptedCredential credential =
                credentialService.resolveActive();
        return apiClient.sendTemplateMessage(
                credential.phoneNumberId(),
                credential.accessToken(),
                to,
                templateName,
                languageCode,
                bodyParams);
    }

    /** Empty when the credential carries no WABA id - there is nothing to ask Meta for. */
    @Override
    public List<WhatsAppTemplate> listApprovedTemplates() {
        MetaWhatsAppCredentialService.DecryptedCredential credential =
                credentialService.resolveActive();
        if (!StringUtils.hasText(credential.wabaId())) {
            return List.of();
        }
        return apiClient
                .listMessageTemplates(credential.wabaId(), credential.accessToken())
                .stream()
                .map(
                        t ->
                                new WhatsAppTemplate(
                                        t.name(),
                                        t.language(),
                                        t.category(),
                                        t.bodyText(),
                                        t.bodyParamCount()))
                .toList();
    }

    @Override
    public Optional<PhoneHealth> phoneHealth() {
        if (!credentialService.isConfigured()) {
            return Optional.empty();
        }
        MetaWhatsAppCredentialService.DecryptedCredential c = credentialService.resolveActive();
        MetaGraphApiClient.PhoneNumberHealth h =
                apiClient.fetchPhoneNumberHealth(c.phoneNumberId(), c.accessToken());
        return Optional.of(new PhoneHealth(h.qualityRating(), h.status()));
    }

    @Override
    public String probeIdentity() {
        if (!credentialService.isConfigured()) {
            throw new ChannelNotConnectedException();
        }
        MetaWhatsAppCredentialService.DecryptedCredential c = credentialService.resolveActive();
        MetaGraphApiClient.PhoneNumberInfo info =
                apiClient.fetchPhoneNumberInfo(c.phoneNumberId(), c.accessToken());
        return info.displayPhoneNumber() != null ? info.displayPhoneNumber() : c.phoneNumberId();
    }
}
