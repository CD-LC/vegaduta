package com.agenticai.core.sarvam;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class SarvamCallLogServiceCountTest {

    @Test
    void countSinceDelegatesToTheRepositoryForTheGivenCapability() {
        SarvamCallLogRepository repository = mock(SarvamCallLogRepository.class);
        Instant since = Instant.parse("2026-10-04T00:00:00Z");
        when(repository.countByCapabilityAndStartedAtAfter(SarvamCallCapability.SPEECH_TO_TEXT, since)).thenReturn(7L);

        SarvamCallLogService service = new SarvamCallLogService(repository);

        assertEquals(7L, service.countSince(SarvamCallCapability.SPEECH_TO_TEXT, since));
        verify(repository).countByCapabilityAndStartedAtAfter(SarvamCallCapability.SPEECH_TO_TEXT, since);
    }
}
