package com.agenticai.core.graphmemory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.prompts.DefaultPromptResolver;
import com.agenticai.core.prompts.InMemoryPromptOverrideStore;
import com.agenticai.core.prompts.PromptOverride;
import com.agenticai.core.prompts.PromptProperties;
import com.agenticai.core.prompts.PromptResolver;
import com.agenticai.core.prompts.PromptScope;
import com.agenticai.core.security.TenantContext;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.client.ChatClient;

/** The triple-extraction prompt comes from the registry, per agent, on the real {@code extractTriples} path. */
class MemoryPromptOverrideTest {

    private static final UUID AGENT = UUID.fromString("00000000-0000-0000-0000-0000000000a1");

    private final InMemoryPromptOverrideStore store = new InMemoryPromptOverrideStore();
    private final DefaultPromptResolver resolver =
            new DefaultPromptResolver(PromptResolver.defaultsOnly().catalog(), store, new PromptProperties());

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    private static ChatClient.ChatClientRequestSpec spec(ChatClient client) {
        ChatClient.ChatClientRequestSpec spec = mock(ChatClient.ChatClientRequestSpec.class);
        ChatClient.CallResponseSpec call = mock(ChatClient.CallResponseSpec.class);
        when(client.prompt()).thenReturn(spec);
        when(spec.system(anyString())).thenReturn(spec);
        when(spec.user(anyString())).thenReturn(spec);
        when(spec.call()).thenReturn(call);
        when(call.content()).thenReturn("[]");
        return spec;
    }

    private static List<String> systemsOf(ChatClient.ChatClientRequestSpec spec) {
        ArgumentCaptor<String> system = ArgumentCaptor.forClass(String.class);
        verify(spec, atLeastOnce()).system(system.capture());
        return system.getAllValues();
    }

    @Test
    void anAgentOverrideOfTheExtractionPromptReachesThatAgentsMemoryWritesOnly() {
        EntityExtractionService service = new EntityExtractionService();
        service.setPromptResolver(resolver);
        TenantContext.set(TenantContext.of("a", "owner", Set.of()));
        store.put(new PromptOverride(PromptScope.AGENT, AGENT.toString(), "memory.entity_extraction", "en", "Only extract people.", true, 1, "owner", Instant.now()));
        resolver.evictAll();

        ChatClient mine = mock(ChatClient.class);
        var mineSpec = spec(mine);
        service.extractTriples(mine, "Priya's manager is Raj", AGENT);
        assertThat(systemsOf(mineSpec)).containsExactly("Only extract people.");

        ChatClient other = mock(ChatClient.class);
        var otherSpec = spec(other);
        service.extractTriples(other, "Priya's manager is Raj", UUID.randomUUID());
        assertThat(systemsOf(otherSpec)).containsExactly(PromptResolver.defaultsOnly().catalog().defaultText("memory.entity_extraction"));

        ChatClient unknown = mock(ChatClient.class);
        var unknownSpec = spec(unknown);
        service.extractTriples(unknown, "Priya's manager is Raj");
        assertThat(systemsOf(unknownSpec)).containsExactly(PromptResolver.defaultsOnly().catalog().defaultText("memory.entity_extraction"));
    }
}
