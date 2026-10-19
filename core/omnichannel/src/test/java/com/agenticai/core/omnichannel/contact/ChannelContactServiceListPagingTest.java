package com.agenticai.core.omnichannel.contact;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.omnichannel.session.ChannelSessionRepository;
import com.agenticai.core.omnichannel.session.ChannelType;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Pageable;

/** Optional limit on the contact directory list: default cap, explicit limit, hard max, ordering preserved by query name. */
class ChannelContactServiceListPagingTest {

    @org.junit.jupiter.api.BeforeEach
    void defaultCapOn() {
        System.setProperty("agenticai.list.default-cap", "true");
    }

    @org.junit.jupiter.api.AfterEach
    void defaultCapOff() {
        System.clearProperty("agenticai.list.default-cap");
    }

    private ChannelContactRepository repo;
    private ChannelContactService service;

    @BeforeEach
    void setUp() {
        repo = mock(ChannelContactRepository.class);
        when(repo.findAllByOrderByCreatedAtDescIdDesc(any(Pageable.class))).thenReturn(List.of());
        when(repo.findByChannelOrderByCreatedAtDescIdDesc(any(ChannelType.class), any(Pageable.class))).thenReturn(List.of());
        when(repo.findByGroupIdOrderByCreatedAtDescIdDesc(any(UUID.class), any(Pageable.class))).thenReturn(List.of());
        service = new ChannelContactService(repo, mock(ContactGroupRepository.class), mock(ChannelSessionRepository.class));
    }

    private int pageSizeFor(Integer limit) {
        service.list(null, null, limit);
        ArgumentCaptor<Pageable> c = ArgumentCaptor.forClass(Pageable.class);
        verify(repo, atLeastOnce()).findAllByOrderByCreatedAtDescIdDesc(c.capture());
        assertEquals(0, c.getValue().getOffset());
        return c.getValue().getPageSize();
    }

    @Test
    void defaultsToFiveHundredAndLegacyOverloadStaysUnbounded() {
        assertEquals(500, pageSizeFor(null));
        service.list(null, null);
        verify(repo).findAllByOrderByCreatedAtDesc();
        verify(repo, org.mockito.Mockito.times(1)).findAllByOrderByCreatedAtDescIdDesc(any(Pageable.class));
    }

    @Test
    void explicitLimitHonouredAndClamped() {
        assertEquals(10, pageSizeFor(10));
        assertEquals(2000, pageSizeFor(1_000_000));
        assertEquals(1, pageSizeFor(0));
    }

    @Test
    void channelAndGroupFiltersAreAlsoBounded() {
        service.list(ChannelType.values()[0], null, null);
        service.list(null, UUID.randomUUID(), 7);
        ArgumentCaptor<Pageable> byChannel = ArgumentCaptor.forClass(Pageable.class);
        verify(repo).findByChannelOrderByCreatedAtDescIdDesc(any(ChannelType.class), byChannel.capture());
        assertEquals(500, byChannel.getValue().getPageSize());
        ArgumentCaptor<Pageable> byGroup = ArgumentCaptor.forClass(Pageable.class);
        verify(repo).findByGroupIdOrderByCreatedAtDescIdDesc(any(UUID.class), byGroup.capture());
        assertEquals(7, byGroup.getValue().getPageSize());
    }
}
