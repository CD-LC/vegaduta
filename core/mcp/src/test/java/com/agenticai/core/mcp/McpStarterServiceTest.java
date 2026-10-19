package com.agenticai.core.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.mcp.dto.McpCatalogEntry;
import com.agenticai.core.mcp.dto.McpServerRegistrationRequest;
import com.agenticai.core.mcp.dto.McpServerRegistrationResponse;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class McpStarterServiceTest {

    private McpRegistryService registry;
    private McpServerRegistrationRepository registrations;
    private McpStarterSeedRepository seeds;
    private McpStarterService service;

    @BeforeEach
    void setUp() {
        registry = mock(McpRegistryService.class);
        registrations = mock(McpServerRegistrationRepository.class);
        seeds = mock(McpStarterSeedRepository.class);
        service = new McpStarterService(registry, registrations, seeds);
        when(registrations.findByName(anyString())).thenReturn(Optional.empty());
        when(seeds.existsById(anyString())).thenReturn(false);
        when(registry.register(any())).thenAnswer(inv -> saved(McpReviewStatus.NOT_REQUIRED));
    }

    private static McpServerRegistrationResponse saved(McpReviewStatus review) {
        McpServerRegistrationResponse r = mock(McpServerRegistrationResponse.class);
        when(r.id()).thenReturn(UUID.randomUUID());
        when(r.reviewStatus()).thenReturn(review);
        return r;
    }

    @Test
    void registersEveryStarterOnceWithTheCatalogLaunchSpec() {
        List<McpStarterService.Seeded> created = service.seedMissing();

        assertEquals(McpStarterService.starterNames(), created.stream().map(McpStarterService.Seeded::name).toList());
        ArgumentCaptor<McpServerRegistrationRequest> req = ArgumentCaptor.forClass(McpServerRegistrationRequest.class);
        verify(registry, times(McpStarterCatalog.STARTERS.size())).register(req.capture());
        for (McpServerRegistrationRequest r : req.getAllValues()) {
            McpStarterCatalog.Starter starter = McpStarterCatalog.STARTERS.stream()
                    .filter(s -> s.name().equals(r.name())).findFirst().orElseThrow();
            assertEquals(starter.entry().dynamicCommand(), r.command());
            assertEquals(starter.entry().dynamicArgs(), r.args());
            assertEquals(starter.enabledByDefault(), r.enabled());
            assertTrue(r.env() == null || r.env().isEmpty(), "no secret is registered");
        }
        verify(seeds, times(McpStarterCatalog.STARTERS.size())).save(any(McpStarterSeed.class));
    }

    @Test
    void fetchAndSequentialThinkingAreRegisteredOffAndTimeOn() {
        List<McpStarterService.Seeded> created = service.seedMissing();
        // fetch runs in the runner, whose network has no egress filter: off until the owner opts in.
        assertFalse(created.stream().filter(s -> s.name().equals("fetch")).findFirst().orElseThrow().enabled());
        assertTrue(created.stream().filter(s -> s.name().equals("time")).findFirst().orElseThrow().enabled());
        assertFalse(created.stream().filter(s -> s.name().equals("sequential-thinking")).findFirst().orElseThrow().enabled());
    }

    @Test
    void aStarterOfferedBeforeIsNotRegisteredAgain() {
        // The owner deleted "fetch" last week: the seed row stays, the registration is gone.
        when(seeds.existsById("fetch")).thenReturn(true);

        List<McpStarterService.Seeded> created = service.seedMissing();

        assertFalse(created.stream().anyMatch(s -> s.name().equals("fetch")));
        ArgumentCaptor<McpServerRegistrationRequest> req = ArgumentCaptor.forClass(McpServerRegistrationRequest.class);
        verify(registry, times(McpStarterCatalog.STARTERS.size() - 1)).register(req.capture());
        assertFalse(req.getAllValues().stream().anyMatch(r -> r.name().equals("fetch")));
    }

    @Test
    void registeredStarterNamesListsOnlyRegistrationsThatStillCarryTheCatalogSpec() {
        McpCatalogEntry timeEntry = McpStarterCatalog.find("time").orElseThrow();
        McpServerRegistration ours = mock(McpServerRegistration.class);
        when(ours.getCommand()).thenReturn(timeEntry.dynamicCommand());
        when(ours.getArgs()).thenReturn("[\"mcp-server-time@2026.8.18\"]");
        when(registrations.findByName("time")).thenReturn(Optional.of(ours));
        McpServerRegistration theirs = mock(McpServerRegistration.class);
        when(theirs.getCommand()).thenReturn("uvx");
        when(theirs.getArgs()).thenReturn("[\"their-own-fetch\"]");
        when(registrations.findByName("fetch")).thenReturn(Optional.of(theirs));

        assertEquals(List.of("time"), service.registeredStarterNames());
    }

    @Test
    void aConnectorTheOwnerAlreadyHasUnderThatNameIsLeftAloneAndCountsAsOffered() {
        when(registrations.findByName("time")).thenReturn(Optional.of(mock(McpServerRegistration.class)));

        List<McpStarterService.Seeded> created = service.seedMissing();

        assertFalse(created.stream().anyMatch(s -> s.name().equals("time")));
        ArgumentCaptor<McpServerRegistrationRequest> req = ArgumentCaptor.forClass(McpServerRegistrationRequest.class);
        verify(registry, times(McpStarterCatalog.STARTERS.size() - 1)).register(req.capture());
        assertFalse(req.getAllValues().stream().anyMatch(r -> r.name().equals("time")));
        ArgumentCaptor<McpStarterSeed> seed = ArgumentCaptor.forClass(McpStarterSeed.class);
        verify(seeds, times(McpStarterCatalog.STARTERS.size())).save(seed.capture());
        assertTrue(seed.getAllValues().stream().anyMatch(s -> s.getStarterKey().equals("time")));
    }

    @Test
    void aSecondPassWithEverythingOfferedDoesNothing() {
        when(seeds.existsById(anyString())).thenReturn(true);

        assertTrue(service.seedMissing().isEmpty());
        verify(registry, never()).register(any());
        verify(seeds, never()).save(any());
    }

    @Test
    void oneFailedRegistrationDoesNotStopTheOthersAndIsRetriedNextStart() {
        when(registry.register(any()))
                .thenThrow(new IllegalStateException("plan cap"))
                .thenAnswer(inv -> saved(McpReviewStatus.NOT_REQUIRED));

        List<McpStarterService.Seeded> created = service.seedMissing();

        assertEquals(McpStarterCatalog.STARTERS.size() - 1, created.size());
        // Only the ones that registered were marked offered; the failed first one is tried again next start.
        verify(seeds, times(McpStarterCatalog.STARTERS.size() - 1)).save(any(McpStarterSeed.class));
    }
}
