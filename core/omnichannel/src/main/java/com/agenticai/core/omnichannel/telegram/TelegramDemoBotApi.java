package com.agenticai.core.omnichannel.telegram;

import com.agenticai.core.omnichannel.demo.DemoTelegramBotApi;
import org.springframework.stereotype.Component;

/** {@link DemoTelegramBotApi} backed by the channel's {@link TelegramApiClient}. */
@Component
public class TelegramDemoBotApi implements DemoTelegramBotApi {

    private final TelegramApiClient apiClient;

    public TelegramDemoBotApi(TelegramApiClient apiClient) {
        this.apiClient = apiClient;
    }

    @Override
    public String fetchBotUsername(String botToken) {
        return apiClient.fetchBotInfo(botToken).username();
    }

    @Override
    public void setWebhook(String botToken, String webhookUrl, String secretToken) {
        apiClient.setWebhook(botToken, webhookUrl, secretToken);
    }

    @Override
    public void sendMessage(String botToken, String chatId, String text) {
        apiClient.sendMessage(botToken, chatId, text);
    }

    @Override
    public void sendTypingIndicator(String botToken, String chatId) {
        apiClient.sendTypingIndicator(botToken, chatId);
    }
}
