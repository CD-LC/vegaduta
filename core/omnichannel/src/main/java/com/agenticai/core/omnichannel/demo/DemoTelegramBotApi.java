package com.agenticai.core.omnichannel.demo;

/**
 * The four Telegram Bot API calls {@link DemoTelegramBot} needs, owned by the demo package so the
 * shared demo bot does not import the Telegram channel adapter (ADR-0011 rule a). Implemented in
 * {@code omnichannel.telegram} by {@code TelegramDemoBotApi}.
 */
public interface DemoTelegramBotApi {

    /** The bot's {@code @username} from {@code getMe}, without the leading {@code @}. */
    String fetchBotUsername(String botToken);

    void setWebhook(String botToken, String webhookUrl, String secretToken);

    void sendMessage(String botToken, String chatId, String text);

    /** Best effort; implementations swallow failures. */
    void sendTypingIndicator(String botToken, String chatId);
}
