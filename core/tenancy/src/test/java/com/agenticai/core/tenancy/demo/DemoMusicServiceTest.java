package com.agenticai.core.tenancy.demo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.agenticai.core.storage.FileStorageService;
import com.agenticai.core.tenancy.PlatformSettingsService;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class DemoMusicServiceTest {

    private final DemoSessionRecorder recorder = mock(DemoSessionRecorder.class);
    private final DemoGeneratedMusicRepository repository = mock(DemoGeneratedMusicRepository.class);
    private final FileStorageService storage = mock(FileStorageService.class);
    private final PlatformSettingsService settings = mock(PlatformSettingsService.class);
    private DemoMusicService service;
    private DemoSession session;

    @BeforeEach
    void setUp() {
        // Unset settings hand back the caller's default, as the real service does.
        when(settings.getInt(anyString(), anyInt())).thenAnswer(inv -> inv.getArgument(1));
        when(settings.getBoolean(anyString(), anyBoolean())).thenAnswer(inv -> inv.getArgument(1));
        when(settings.getString(anyString(), any())).thenAnswer(inv -> inv.getArgument(1));
        service = new DemoMusicService(recorder, repository, storage, settings, true, 1, 300, 15, 40, 5);
        Instant now = Instant.now();
        session = new DemoSession("hash", UUID.randomUUID(), "demo_test", null, "127.0.0.1", null, now, now.plusSeconds(3600));
    }

    @Test
    void defaultsAreOneFifteenSecondClipPerSandbox() {
        assertTrue(service.isEnabled());
        assertEquals(1, service.perSessionLimit());
        assertEquals(15, service.clipSeconds());
        assertFalse(service.isCapReached(session));
        session.recordMusic();
        assertTrue(service.isCapReached(session));
    }

    @Test
    void clipLengthIsHardClampedWhateverAnOperatorTypes() {
        when(settings.getInt(eq("demo.music.clip-seconds"), anyInt())).thenReturn(600);
        assertEquals(30, service.clipSeconds());
        when(settings.getInt(eq("demo.music.clip-seconds"), anyInt())).thenReturn(0);
        assertEquals(3, service.clipSeconds());
    }

    @Test
    void globalDailyBudgetIsSharedAcrossAllSandboxes() {
        when(repository.countByCreatedAtAfter(any())).thenReturn(39L);
        assertTrue(service.isGlobalBudgetAvailable());
        when(repository.countByCreatedAtAfter(any())).thenReturn(40L);
        assertFalse(service.isGlobalBudgetAvailable());
    }

    @Test
    void blankAndOverlongPromptsAreRefusedWithASentenceNotAnException() {
        assertNotNull(service.refusalFor("   "));
        assertNotNull(service.refusalFor("x".repeat(301)));
        assertNull(service.refusalFor("gentle sitar and tabla, morning raga"));
    }

    @Test
    void storedContentTypeIsAlwaysMp3AndTheKeyIsScopedToTheSandbox() {
        UUID id = UUID.randomUUID();
        when(recorder.recordMusicRow(any(), anyString(), anyString(), anyString(), anyString(), anyInt(), anyString())).thenReturn(id);

        assertEquals(id, service.store(session, new byte[] {1, 2}, "jazz", "music_v1", 15, "127.0.0.1"));

        verify(storage).put(org.mockito.ArgumentMatchers.startsWith("demo-music/demo_test/"), any(), eq("audio/mpeg"));
        verify(recorder).recordMusicRow(eq(session.getId()), anyString(), eq("audio/mpeg"), eq("jazz"), eq("music_v1"), eq(15), eq("127.0.0.1"));
    }

    @Test
    void emptyOrOversizedPayloadsAreRejectedBeforeStorage() {
        assertThrows(IllegalArgumentException.class, () -> service.store(session, new byte[0], "jazz", "music_v1", 15, "ip"));
        assertThrows(IllegalArgumentException.class,
            () -> service.store(session, new byte[8 * 1024 * 1024 + 1], "jazz", "music_v1", 15, "ip"));
        verifyNoInteractions(storage);
    }
}
