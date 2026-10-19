package com.agenticai.core.security;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class LogMaskingTest {

    @Test
    void emailKeepsOnlyTheFirstCharacterAndTheDomain() {
        assertThat(LogMasking.email("jane.doe@example.com")).isEqualTo("j***@example.com");
        assertThat(LogMasking.email(" a@b.io ")).isEqualTo("a***@b.io");
        assertThat(LogMasking.email(null)).isEqualTo("null");
    }

    @Test
    void nonEmailValuesFallBackToLastFour() {
        assertThat(LogMasking.email("imap-user-12345")).isEqualTo("***2345");
        assertThat(LogMasking.email("@nope")).isEqualTo("***");
    }

    @Test
    void lastFourNeverRevealsShortValues() {
        assertThat(LogMasking.lastFour("+919876543210")).isEqualTo("***3210");
        assertThat(LogMasking.lastFour("abc")).isEqualTo("***");
        assertThat(LogMasking.lastFour(null)).isEqualTo("null");
    }

    @Test
    void sizeNeverIncludesTheBody() {
        assertThat(LogMasking.size("secret user text")).isEqualTo("<16 chars>").doesNotContain("secret");
        assertThat(LogMasking.size(null)).isEqualTo("<null>");
    }

    @Test
    void sec050PathMasksCapabilityTokensAndDropsQuery() {
        String token = "wh_9f8e7d6c5b4a39281706f5e4d3c2b1a0";
        assertThat(LogMasking.path("/webhook/workflows/" + token)).isEqualTo("/webhook/workflows/***b1a0")
            .doesNotContain(token);
        assertThat(LogMasking.path("/webhook/workflows/" + token + "?x=1")).isEqualTo("/webhook/workflows/***b1a0");
        assertThat(LogMasking.path("/api/share/agents/abcdefgh12345678/chat")).isEqualTo("/api/share/agents/***5678/chat");
        assertThat(LogMasking.path("/api/share/workflows/abcdefgh12345678/run")).isEqualTo("/api/share/workflows/***5678/run");
        assertThat(LogMasking.path("/api/demo/share/abcdefgh12345678")).isEqualTo("/api/demo/share/***5678");
        // oidc webhooks carry a workflow id, not a credential
        assertThat(LogMasking.path("/webhook/workflows/oidc/123")).isEqualTo("/webhook/workflows/oidc/123");
        assertThat(LogMasking.path("/api/agents/42?token=secret")).isEqualTo("/api/agents/42");
        assertThat(LogMasking.path(null)).isEqualTo("null");
    }

    @Test
    void secretsScrubsQueryKeysBearerTokensHeadersAndVendorKeyShapes() {
        String geminiKey = "AIzaSyD-1234567890abcdefghijklmnopqrstu";
        String openAiKey = "sk-proj-abcdefghijklmnopqrstuvwxyz0123456789";

        assertThat(LogMasking.secrets("Illegal character in query: wss://host/x?key=" + geminiKey + "&alt=sse"))
            .doesNotContain(geminiKey).contains("key=***").contains("&alt=sse");
        assertThat(LogMasking.secrets("failed: Authorization: Bearer abcdef1234567890.zzz"))
            .doesNotContain("abcdef1234567890");
        assertThat(LogMasking.secrets("request headers x-api-key: " + openAiKey + ", accept: json"))
            .doesNotContain(openAiKey);
        assertThat(LogMasking.secrets("upstream said bad key " + openAiKey + " sorry")).doesNotContain(openAiKey);
        assertThat(LogMasking.secrets("bot token xoxb-1234567890-abcdefghij leaked")).doesNotContain("xoxb-1234567890");
        assertThat(LogMasking.secrets("https://h/p?access_token=abc123def456&x=1")).contains("access_token=***");
    }

    @Test
    void secretsLeavesOrdinaryTextAndNullAlone() {
        assertThat(LogMasking.secrets(null)).isNull();
        assertThat(LogMasking.secrets("")).isEmpty();
        String plain = "Could not reach Gemini at https://generativelanguage.googleapis.com (model gemini-2.5): timeout";
        assertThat(LogMasking.secrets(plain)).isEqualTo(plain);
        assertThat(LogMasking.secrets("the task-key is fine and so is sk-short")).isEqualTo("the task-key is fine and so is sk-short");
    }
}
