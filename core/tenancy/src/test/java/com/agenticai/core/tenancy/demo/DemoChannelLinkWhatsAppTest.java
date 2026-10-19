package com.agenticai.core.tenancy.demo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** WHATSAPP channel semantics of {@link DemoChannelLinkService}: single-use codes, one live binding per external id. */
class DemoChannelLinkWhatsAppTest {

    private DemoChannelLinkRepository repo;
    private DemoChannelLinkService service;
    private final Map<UUID, DemoChannelLink> store = new ConcurrentHashMap<>();
    private final Map<UUID, DemoSession> sessions = new ConcurrentHashMap<>();

    @BeforeEach
    void setUp() {
        repo = mock(DemoChannelLinkRepository.class);
        when(repo.saveAndFlush(any(DemoChannelLink.class))).thenAnswer(inv -> {
            DemoChannelLink l = inv.getArgument(0);
            store.put(l.getDemoSessionId(), l);
            return l;
        });
        when(repo.findByDemoSessionIdAndChannel(any(), any())).thenAnswer(inv ->
            Optional.ofNullable(store.get((UUID) inv.getArgument(0))));
        when(repo.findByChannelAndPairingCode(any(), any())).thenAnswer(inv -> store.values().stream()
            .filter(l -> l.getChannel() == inv.getArgument(0) && inv.getArgument(1).equals(l.getPairingCode()))
            .findFirst());
        when(repo.findByChannelAndExternalIdAndState(any(), any(), any())).thenAnswer(inv -> store.values().stream()
            .filter(l -> l.getChannel() == inv.getArgument(0)
                && inv.getArgument(1).equals(l.getExternalId())
                && l.getState() == inv.getArgument(2))
            .findFirst());
        service = new DemoChannelLinkService(repo, mock(DemoChannelMessageRepository.class), 40);
    }

    private DemoSession session() {
        DemoSession s = mock(DemoSession.class);
        UUID id = UUID.randomUUID();
        when(s.getId()).thenReturn(id);
        when(s.isExpired(any(Instant.class))).thenReturn(false);
        sessions.put(id, s);
        return s;
    }

    private Optional<DemoSession> lookup(UUID id) {
        return Optional.ofNullable(sessions.get(id));
    }

    @Test
    void codeIsSingleUse() {
        DemoSession s = session();
        DemoChannelLink link = service.issuePairingCode(s, DemoChannelType.WHATSAPP);
        String code = link.getPairingCode();
        assertNotNull(code);
        assertTrue(code.matches("VD-[A-Z0-9]{4}"));

        var first = service.redeem(DemoChannelType.WHATSAPP, code, "hashA", null, this::lookup);
        assertEquals(DemoChannelLinkService.RedeemOutcome.LINKED, first.outcome());
        assertEquals("hashA", first.link().getExternalId());
        assertNull(first.link().getPairingCode());

        var second = service.redeem(DemoChannelType.WHATSAPP, code, "hashB", null, this::lookup);
        assertEquals(DemoChannelLinkService.RedeemOutcome.UNKNOWN_CODE, second.outcome());
    }

    @Test
    void oneLiveBindingPerExternalId() {
        DemoSession s1 = session();
        DemoSession s2 = session();
        String c1 = service.issuePairingCode(s1, DemoChannelType.WHATSAPP).getPairingCode();
        String c2 = service.issuePairingCode(s2, DemoChannelType.WHATSAPP).getPairingCode();
        service.redeem(DemoChannelType.WHATSAPP, c1, "hashA", null, this::lookup);
        var second = service.redeem(DemoChannelType.WHATSAPP, c2, "hashA", null, this::lookup);
        assertEquals(DemoChannelLinkService.RedeemOutcome.LINKED, second.outcome());
        assertEquals(DemoChannelLinkState.REVOKED, store.get(s1.getId()).getState());
        assertEquals("hashA", service.findLiveBinding(DemoChannelType.WHATSAPP, "hashA").get().getExternalId());
        assertEquals(s2.getId(), service.findLiveBinding(DemoChannelType.WHATSAPP, "hashA").get().getDemoSessionId());
    }

    @Test
    void expiredSessionCannotBeRedeemed() {
        DemoSession s = session();
        String code = service.issuePairingCode(s, DemoChannelType.WHATSAPP).getPairingCode();
        when(s.isExpired(any(Instant.class))).thenReturn(true);
        var r = service.redeem(DemoChannelType.WHATSAPP, code, "hashA", null, this::lookup);
        assertEquals(DemoChannelLinkService.RedeemOutcome.SESSION_ENDED, r.outcome());
    }

    @Test
    void extractsCodeFromFreeTextButNotFromPlainWords() {
        assertEquals("VD-7F3K", DemoChannelLinkService.extractPairingCode("VD-7F3K"));
        assertEquals("VD-7F3K", DemoChannelLinkService.extractPairingCode("hi, my code is vd-7f3k please"));
        assertEquals("VD-7F3K", DemoChannelLinkService.extractPairingCode("VD 7F3K."));
        assertEquals("VD-7F3K", DemoChannelLinkService.extractPairingCode("7F3K"));
        assertNull(DemoChannelLinkService.extractPairingCode("hello there, can you help with my order"));
        assertNull(DemoChannelLinkService.extractPairingCode("VD-7F3K7F3K"));
        assertNull(DemoChannelLinkService.extractPairingCode(null));
    }
}
