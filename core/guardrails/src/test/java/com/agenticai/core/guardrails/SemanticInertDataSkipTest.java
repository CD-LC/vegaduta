package com.agenticai.core.guardrails;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;

class SemanticInertDataSkipTest {

    @Test
    void plainDataIsNotSentToTheJudge() {
        ChatClient client = mock(ChatClient.class);
        String data = "{\"order_id\":\"ORD-1001\",\"status\":\"shipped\",\"total\":42.5}";
        assertEquals(
                data, new SemanticGuardrailService().sanitizeToolOutput("lookup", data, client));
        verifyNoInteractions(client);
    }
}
