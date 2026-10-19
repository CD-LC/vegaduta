package com.agenticai.core.elevenlabs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link ElevenLabsVoiceService} covering voice cloning
 * (upload via client and persist), listing, deletion, and helper methods
 * (findById, hasAnyVoice, mostRecent). Plain unit tests with mocked
 * repository and client - no Spring context.
 */
class ElevenLabsVoiceServiceTest {

    private ElevenLabsVoiceRepository repository;
    private ElevenLabsClient client;
    private ElevenLabsVoiceService service;

    @BeforeEach
    void setUp() {
        repository = mock(ElevenLabsVoiceRepository.class);
        client = mock(ElevenLabsClient.class);
        service = new ElevenLabsVoiceService(repository, client);
    }

    // ------------------------------------------------------------------
    // cloneVoice - upload sample and persist
    // ------------------------------------------------------------------

    @Test
    void cloneVoiceCallsClientAndPersistsResult() {
        String voiceName = "my-voice";
        byte[] sampleBytes = "audio-data".getBytes();
        String voiceId = "voice-xyz-123";

        when(client.cloneVoice(voiceName, sampleBytes, "audio/mp3", "sample.mp3"))
            .thenReturn(voiceId);
        when(repository.save(any(ElevenLabsVoice.class)))
            .thenAnswer(inv -> inv.getArgument(0));

        ElevenLabsVoice voice = service.cloneVoice(voiceName, sampleBytes, "audio/mp3", "sample.mp3");

        assertNotNull(voice);
        assertEquals(voiceId, voice.getElevenLabsVoiceId());
        assertEquals(voiceName, voice.getName());
        verify(client, times(1)).cloneVoice(voiceName, sampleBytes, "audio/mp3", "sample.mp3");
        verify(repository, times(1)).save(any(ElevenLabsVoice.class));
    }

    @Test
    void cloneVoicePropagatesClientException() {
        String voiceName = "my-voice";
        byte[] sampleBytes = "audio-data".getBytes();

        when(client.cloneVoice(anyString(), any(), anyString(), anyString()))
            .thenThrow(new ElevenLabsApiException(401, "Unauthorized"));

        ElevenLabsApiException thrown = assertThrows(ElevenLabsApiException.class,
            () -> service.cloneVoice(voiceName, sampleBytes, "audio/mp3", "sample.mp3"));
        assertEquals(401, thrown.getUpstreamStatus());
        verify(repository, times(0)).save(any());
    }

    // ------------------------------------------------------------------
    // list - retrieve all voices
    // ------------------------------------------------------------------

    @Test
    void listReturnsAllVoicesInRecentFirstOrder() {
        ElevenLabsVoice voice1 = ElevenLabsVoice.create("voice-1", "recent-voice");
        ElevenLabsVoice voice2 = ElevenLabsVoice.create("voice-2", "older-voice");

        List<ElevenLabsVoice> voices = List.of(voice1, voice2);
        when(repository.findAllByOrderByCreatedAtDesc()).thenReturn(voices);

        List<ElevenLabsVoice> result = service.list();

        assertEquals(2, result.size());
        assertEquals(voice1, result.get(0));
        assertEquals(voice2, result.get(1));
    }

    @Test
    void listReturnsEmptyWhenNoVoicesExist() {
        when(repository.findAllByOrderByCreatedAtDesc()).thenReturn(List.of());

        List<ElevenLabsVoice> result = service.list();

        assertTrue(result.isEmpty());
    }

    // ------------------------------------------------------------------
    // findById - lookup by ID
    // ------------------------------------------------------------------

    @Test
    void findByIdReturnsVoiceWhenFound() {
        UUID voiceId = UUID.randomUUID();
        ElevenLabsVoice voice = ElevenLabsVoice.create("voice-xyz-123", "my-voice");

        when(repository.findById(voiceId)).thenReturn(Optional.of(voice));

        Optional<ElevenLabsVoice> result = service.findById(voiceId);

        assertTrue(result.isPresent());
        assertEquals(voice, result.get());
    }

    @Test
    void findByIdReturnsEmptyWhenNotFound() {
        UUID voiceId = UUID.randomUUID();

        when(repository.findById(voiceId)).thenReturn(Optional.empty());

        Optional<ElevenLabsVoice> result = service.findById(voiceId);

        assertFalse(result.isPresent());
    }

    // ------------------------------------------------------------------
    // delete - delete voice from ElevenLabs and remove local reference
    // ------------------------------------------------------------------

    @Test
    void deleteCallsClientThenRemovesLocalReference() {
        UUID voiceId = UUID.randomUUID();
        ElevenLabsVoice voice = ElevenLabsVoice.create("voice-xyz-123", "my-voice");

        when(repository.findById(voiceId)).thenReturn(Optional.of(voice));

        service.delete(voiceId);

        verify(client, times(1)).deleteVoice("voice-xyz-123");
        verify(repository, times(1)).delete(voice);
    }

    @Test
    void deletePropagatesClientExceptionAndDoesNotRemoveLocalReference() {
        UUID voiceId = UUID.randomUUID();
        ElevenLabsVoice voice = ElevenLabsVoice.create("voice-xyz-123", "my-voice");

        when(repository.findById(voiceId)).thenReturn(Optional.of(voice));
        doThrow(new ElevenLabsApiException(404, "Not Found"))
            .when(client).deleteVoice("voice-xyz-123");

        ElevenLabsApiException thrown = assertThrows(ElevenLabsApiException.class,
            () -> service.delete(voiceId));

        assertEquals(404, thrown.getUpstreamStatus());
        verify(repository, times(0)).delete(any());
    }

    @Test
    void deleteIsNoOpWhenVoiceNotFound() {
        UUID voiceId = UUID.randomUUID();

        when(repository.findById(voiceId)).thenReturn(Optional.empty());

        service.delete(voiceId); // should not throw

        verify(client, times(0)).deleteVoice(anyString());
        verify(repository, times(0)).delete(any());
    }

    // ------------------------------------------------------------------
    // hasAnyVoice - cheap gate for tool availability
    // ------------------------------------------------------------------

    @Test
    void hasAnyVoiceReturnsTrueWhenVoicesExist() {
        when(repository.count()).thenReturn(1L);

        assertTrue(service.hasAnyVoice());
    }

    @Test
    void hasAnyVoiceReturnsFalseWhenNoVoicesExist() {
        when(repository.count()).thenReturn(0L);

        assertFalse(service.hasAnyVoice());
    }

    @Test
    void hasAnyVoiceReturnsTrueWhenMultipleVoicesExist() {
        when(repository.count()).thenReturn(3L);

        assertTrue(service.hasAnyVoice());
    }

    // ------------------------------------------------------------------
    // mostRecent - get the most recently cloned voice
    // ------------------------------------------------------------------

    @Test
    void mostRecentReturnsFirstVoiceInDescOrder() {
        ElevenLabsVoice voice1 = ElevenLabsVoice.create("voice-1", "most-recent");
        ElevenLabsVoice voice2 = ElevenLabsVoice.create("voice-2", "older");

        List<ElevenLabsVoice> voices = List.of(voice1, voice2);
        when(repository.findAllByOrderByCreatedAtDesc()).thenReturn(voices);

        ElevenLabsVoice result = service.mostRecent();

        assertEquals(voice1, result);
    }

    @Test
    void mostRecentThrowsWhenNoVoicesClonedYet() {
        when(repository.findAllByOrderByCreatedAtDesc()).thenReturn(List.of());

        ElevenLabsNotConfiguredException thrown = assertThrows(ElevenLabsNotConfiguredException.class,
            () -> service.mostRecent());

        assertTrue(thrown.getMessage().contains("voice"));
    }
}
