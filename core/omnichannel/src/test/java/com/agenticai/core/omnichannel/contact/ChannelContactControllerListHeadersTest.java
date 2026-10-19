package com.agenticai.core.omnichannel.contact;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.agents.core.AgentRepository;
import com.agenticai.core.omnichannel.session.ChannelSessionRepository;
import com.agenticai.core.omnichannel.session.ChannelType;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** X-Total-Count / X-Has-More and ?offset= on GET /api/channels/contacts. */
class ChannelContactControllerListHeadersTest {

    @org.junit.jupiter.api.BeforeEach
    void defaultCapOn() {
        System.setProperty("agenticai.list.default-cap", "true");
    }

    @org.junit.jupiter.api.AfterEach
    void defaultCapOff() {
        System.clearProperty("agenticai.list.default-cap");
    }

    private ChannelContactService service;
    private ChannelContactController controller;
    private final ChannelContact row = new ChannelContact(ChannelType.values()[0], "+15550001", "A", null, null, "manual");

    @BeforeEach
    void setUp() {
        service = mock(ChannelContactService.class);
        controller = new ChannelContactController(service, mock(ChannelSessionRepository.class), mock(AgentRepository.class));
    }

    @Test
    void shortPageSkipsCountAndReportsNoMore() {
        when(service.list(any(), any(), eq(5), any())).thenReturn(List.of(row));
        var res = controller.list(null, null, 5, null);
        assertEquals("1", res.getHeaders().getFirst("X-Total-Count"));
        assertEquals("false", res.getHeaders().getFirst("X-Has-More"));
        verify(service, never()).count(any(), any());
    }

    @Test
    void fullPageCountsAndFlagsMore() {
        when(service.list(any(), any(), eq(1), any())).thenReturn(List.of(row));
        when(service.count(any(), any())).thenReturn(2500L);
        var res = controller.list(null, null, 1, null);
        assertEquals("2500", res.getHeaders().getFirst("X-Total-Count"));
        assertEquals("true", res.getHeaders().getFirst("X-Has-More"));
    }

    @Test
    void offsetIsPassedThroughAndAlwaysCounts() {
        when(service.list(any(), any(), eq(10), eq(2000L))).thenReturn(List.of(row));
        when(service.count(any(), any())).thenReturn(2001L);
        var res = controller.list(null, null, 10, 2000L);
        assertEquals("2001", res.getHeaders().getFirst("X-Total-Count"));
        assertEquals("false", res.getHeaders().getFirst("X-Has-More"));
    }
}
