package com.agenticai.core.omnichannel.meta;

import com.agenticai.core.omnichannel.demo.DemoWhatsAppCloudApi;
import org.springframework.stereotype.Component;

/** {@link DemoWhatsAppCloudApi} backed by the channel's {@link MetaGraphApiClient}. */
@Component
public class MetaDemoWhatsAppCloudApi implements DemoWhatsAppCloudApi {

    private final MetaGraphApiClient apiClient;

    public MetaDemoWhatsAppCloudApi(MetaGraphApiClient apiClient) {
        this.apiClient = apiClient;
    }

    @Override
    public String sendTextMessage(
            String phoneNumberId, String accessToken, String toE164, String body) {
        return apiClient.sendTextMessage(phoneNumberId, accessToken, toE164, body);
    }
}
