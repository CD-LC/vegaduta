package com.agenticai.core.voice;

import com.agenticai.core.security.edition.ConditionalOnEditionFeature;
import com.agenticai.core.security.edition.EditionFeature;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * Who each in-app call room was issued to. {@link VoiceCallController#issueToken} mints the room
 * name ({@code voice|sight-<agentId>-<8 hex>}) and nothing else records the caller, so without this
 * a member who learned a room name could start or stop a recording of someone else's call. The room
 * name's format is fixed by the voice-agent worker, so the binding lives here instead of in the
 * name.
 *
 * <p>Fails closed: if Redis is unreachable at issue time the room has no owner record and cannot be
 * recorded; the call itself is unaffected.
 */
@ConditionalOnEditionFeature(EditionFeature.VOICE_REALTIME_SHARE)
@Component
public class VoiceCallRoomRegistry {

    private static final Logger log = LoggerFactory.getLogger(VoiceCallRoomRegistry.class);

    static final String KEY_PREFIX = "agentic:voice-room:";

    /** Longer than any call; the record only has to outlive the call it describes. */
    static final Duration TTL = Duration.ofHours(12);

    private final StringRedisTemplate redisTemplate;

    public VoiceCallRoomRegistry(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    public void register(String roomName, String tenantId, String subject) {
        try {
            redisTemplate.opsForValue().set(KEY_PREFIX + roomName, owner(tenantId, subject), TTL);
        } catch (RuntimeException e) {
            log.warn(
                    "Could not record the owner of call room {}; it cannot be recorded",
                    roomName,
                    e);
        }
    }

    /** True only when the room was issued to exactly this tenant and subject. */
    public boolean isIssuedTo(String roomName, String tenantId, String subject) {
        if (roomName == null || tenantId == null || subject == null) {
            return false;
        }
        try {
            return owner(tenantId, subject)
                    .equals(redisTemplate.opsForValue().get(KEY_PREFIX + roomName));
        } catch (RuntimeException e) {
            log.warn("Could not read the owner of call room {}", roomName, e);
            return false;
        }
    }

    private static String owner(String tenantId, String subject) {
        return tenantId + "\n" + subject;
    }
}
