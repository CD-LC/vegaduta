package com.agenticai.core.omnichannel.telegram;

import com.agenticai.core.omnichannel.outbound.TenantBotToken;
import java.util.Optional;
import org.springframework.stereotype.Component;

/** The tenant's own connected Telegram bot token, if any. */
@Component
public class TelegramTenantBotToken implements TenantBotToken {

    private final TelegramCredentialService credentialService;

    public TelegramTenantBotToken(TelegramCredentialService credentialService) {
        this.credentialService = credentialService;
    }

    @Override
    public String channel() {
        return "telegram";
    }

    @Override
    public Optional<String> activeBotToken() {
        try {
            return Optional.ofNullable(credentialService.resolveActive().botToken());
        } catch (RuntimeException e) {
            // No per-tenant bot connected (TelegramNotConfiguredException) or unreadable.
            return Optional.empty();
        }
    }
}
