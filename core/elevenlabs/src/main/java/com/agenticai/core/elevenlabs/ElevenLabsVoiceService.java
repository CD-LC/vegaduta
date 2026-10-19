package com.agenticai.core.elevenlabs;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Voice cloning setup - the real "connect a resource" action this feature
 * needs beyond BYOK credential activation (see {@code core/elevenlabs}
 * module's javadoc for why this can't just be a tool call). Uploads a
 * sample clip to ElevenLabs via {@link ElevenLabsClient#cloneVoice}, then
 * persists the returned {@code voice_id} as an {@link ElevenLabsVoice} row
 * this tenant's agents can subsequently speak in via {@code
 * ElevenLabsSpeakToolCallback}.
 */
@Service
public class ElevenLabsVoiceService {

    private final ElevenLabsVoiceRepository repository;
    private final ElevenLabsClient client;

    public ElevenLabsVoiceService(ElevenLabsVoiceRepository repository, ElevenLabsClient client) {
        this.repository = repository;
        this.client = client;
    }

    @Transactional
    public ElevenLabsVoice cloneVoice(String name, byte[] sampleBytes, String contentType, String filename) {
        String voiceId = client.cloneVoice(name, sampleBytes, contentType, filename);
        return repository.save(ElevenLabsVoice.create(voiceId, name));
    }

    @Transactional(readOnly = true)
    public List<ElevenLabsVoice> list() {
        return repository.findAllByOrderByCreatedAtDesc();
    }

    /** {@code ElevenLabsSpeakToolCallback}'s explicit-voice_id lookup - a plain pass-through, kept here rather than exposing the repository directly to the tool callback (same "tool callbacks depend on this module's services, never its repositories" convention every other tool callback in this codebase follows). */
    @Transactional(readOnly = true)
    public Optional<ElevenLabsVoice> findById(UUID id) {
        return repository.findById(id);
    }

    /** Deletes both the ElevenLabs-side voice model and this tenant's local reference row - ElevenLabs' own DELETE call runs first so a failure there leaves the local row intact rather than silently orphaning the remote voice. */
    @Transactional
    public void delete(UUID id) {
        repository.findById(id).ifPresent(voice -> {
            client.deleteVoice(voice.getElevenLabsVoiceId());
            repository.delete(voice);
        });
    }

    /** Cheap, non-throwing gate for {@code AgentToolProvider#resolveElevenLabsTools} - "has this tenant cloned at least one voice yet". */
    @Transactional(readOnly = true)
    public boolean hasAnyVoice() {
        return repository.count() > 0;
    }

    /** The most recently cloned voice - {@code ElevenLabsSpeakToolCallback}'s default when the caller doesn't specify a voice_id. */
    @Transactional(readOnly = true)
    public ElevenLabsVoice mostRecent() {
        List<ElevenLabsVoice> voices = repository.findAllByOrderByCreatedAtDesc();
        if (voices.isEmpty()) {
            throw new ElevenLabsNotConfiguredException("No ElevenLabs voice has been cloned yet - POST /api/elevenlabs/voices first.");
        }
        return voices.get(0);
    }
}
