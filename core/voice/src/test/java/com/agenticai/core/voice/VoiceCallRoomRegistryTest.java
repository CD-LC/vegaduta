package com.agenticai.core.voice;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

class VoiceCallRoomRegistryTest {

    @SuppressWarnings("unchecked")
    private final ValueOperations<String, String> values = mock(ValueOperations.class);

    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);

    private VoiceCallRoomRegistry registry() {
        when(redis.opsForValue()).thenReturn(values);
        return new VoiceCallRoomRegistry(redis);
    }

    @Test
    void aRoomIsIssuedToExactlyTheTenantAndSubjectThatRegisteredIt() {
        VoiceCallRoomRegistry registry = registry();
        registry.register("voice-a-1", "acme", "alice");
        verify(values)
                .set(
                        VoiceCallRoomRegistry.KEY_PREFIX + "voice-a-1",
                        "acme\nalice",
                        VoiceCallRoomRegistry.TTL);

        when(values.get(VoiceCallRoomRegistry.KEY_PREFIX + "voice-a-1")).thenReturn("acme\nalice");
        assertThat(registry.isIssuedTo("voice-a-1", "acme", "alice")).isTrue();
        assertThat(registry.isIssuedTo("voice-a-1", "acme", "bob")).isFalse();
        assertThat(registry.isIssuedTo("voice-a-1", "other", "alice")).isFalse();
    }

    @Test
    void anUnknownRoomOrARedisOutageIsNotIssuedToAnyone() {
        VoiceCallRoomRegistry registry = registry();
        assertThat(registry.isIssuedTo("voice-a-2", "acme", "alice")).isFalse();
        when(values.get(VoiceCallRoomRegistry.KEY_PREFIX + "voice-a-3"))
                .thenThrow(new RedisConnectionFailureException("down"));
        assertThat(registry.isIssuedTo("voice-a-3", "acme", "alice")).isFalse();
    }
}
