package com.agenticai.core.agents.demo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.agenticai.core.agents.core.Agent;
import com.agenticai.core.agents.core.AgentRepository;
import com.agenticai.core.agents.core.AgentService;
import com.agenticai.core.agents.demo.studio.DemoStudioConnectorService;
import com.agenticai.core.agents.demo.studio.DemoStudioDocumentService;
import com.agenticai.core.agents.dto.AgentCreateRequest;
import com.agenticai.core.agents.dto.AgentResponse;
import com.agenticai.core.providers.ProviderName;
import com.agenticai.core.security.ExperienceMode;
import com.agenticai.core.tenancy.demo.DemoPersona;
import com.agenticai.core.tenancy.demo.DemoWorkspaceSeeder.SeededAgent;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;

/** How the sandbox agent is named, prompted and cleaned up. */
class DemoAgentSeederTest {

    private AgentService agentService;
    private AgentRepository repository;
    private DemoStudioDocumentService documents;
    private DemoStudioConnectorService connectors;
    private DemoAgentSeeder seeder;

    @SuppressWarnings("unchecked")
    private static <T> ObjectProvider<T> provider(T value) {
        ObjectProvider<T> p = mock(ObjectProvider.class);
        when(p.getIfAvailable()).thenReturn(value);
        return p;
    }

    @BeforeEach
    void setUp() {
        agentService = mock(AgentService.class);
        repository = mock(AgentRepository.class);
        documents = mock(DemoStudioDocumentService.class);
        connectors = mock(DemoStudioConnectorService.class);
        seeder =
                new DemoAgentSeeder(
                        agentService, repository, provider(documents), provider(connectors));
        AgentResponse created = mock(AgentResponse.class);
        UUID id = UUID.randomUUID();
        when(created.id()).thenReturn(id);
        when(created.name()).thenReturn("created");
        when(agentService.create(any(AgentCreateRequest.class))).thenReturn(created);
    }

    private AgentCreateRequest seedAndCapture(String business, DemoPersona persona) {
        SeededAgent seeded = seeder.seedDemoAgent("demo_x", business, persona);
        assertThat(seeded.agentName()).isEqualTo("created");
        ArgumentCaptor<AgentCreateRequest> request =
                ArgumentCaptor.forClass(AgentCreateRequest.class);
        verify(agentService, org.mockito.Mockito.atLeastOnce()).create(request.capture());
        return request.getValue();
    }

    @Test
    void aTypedBusinessNameWinsOverThePersonaAndNamesTheAssistantAfterIt() {
        DemoPersona persona = DemoPersona.values()[0];

        AgentCreateRequest request = seedAndCapture("Priya's Bakery", persona);

        assertThat(request.name()).isEqualTo("Priya's Bakery assistant");
        assertThat(request.systemPrompt())
                .contains("assistant for Priya's Bakery")
                .contains("Never invent facts about Priya's Bakery")
                .contains(persona.promptBlock().strip().substring(0, 20));
        assertThat(request.providerName()).isEqualTo(ProviderName.PLATFORM_HOSTED);
        assertThat(request.experienceMode()).isEqualTo(ExperienceMode.SIMPLE);
    }

    @Test
    void withoutABusinessNameThePersonaSuppliesTheLabelAndAgentName() {
        DemoPersona persona = DemoPersona.values()[0];

        AgentCreateRequest request = seedAndCapture(null, persona);

        assertThat(request.name()).isEqualTo(persona.defaultAgentName());
        assertThat(request.systemPrompt()).contains("assistant for " + persona.businessLabel());
    }

    @Test
    void withNeitherTheGenericDefaultsApplyAndNoPersonaParagraphIsAdded() {
        AgentCreateRequest request = seedAndCapture(null, null);

        assertThat(request.name()).isEqualTo("Customer assistant");
        assertThat(request.systemPrompt())
                .contains("assistant for your business")
                .contains("This is a sandbox")
                .contains("UPI payment");
    }

    @Test
    void cleaningUpASubjectRemovesStudioDataThenTheAgentAndSurvivesOneFailure() {
        Agent first = mock(Agent.class);
        Agent second = mock(Agent.class);
        UUID firstId = UUID.randomUUID();
        UUID secondId = UUID.randomUUID();
        when(first.getId()).thenReturn(firstId);
        when(second.getId()).thenReturn(secondId);
        when(repository.findByCreatedBy("demo:s1")).thenReturn(List.of(first, second));
        doThrow(new IllegalStateException("locked")).when(documents).deleteAllFor(firstId);

        seeder.deleteAgentsCreatedBy("demo_x", "demo:s1");

        verify(repository, never()).delete(first);
        verify(connectors).deleteAllFor(secondId);
        verify(repository).delete(second);
        verify(repository).flush();
    }

    @Test
    void cleanupWithoutStudioServicesStillDeletesTheAgent() {
        seeder = new DemoAgentSeeder(agentService, repository, provider(null), provider(null));
        Agent agent = mock(Agent.class);
        when(repository.findByCreatedBy("demo:s1")).thenReturn(List.of(agent));

        seeder.deleteAgentsCreatedBy("demo_x", "demo:s1");

        verify(repository).delete(agent);
    }

    @Test
    void aBlankSubjectCleansNothing() {
        seeder.deleteAgentsCreatedBy("demo_x", " ");
        seeder.deleteAgentsCreatedBy("demo_x", null);

        verifyNoInteractions(repository);
    }

    @Test
    void deletingASingleAgentIsBestEffort() {
        UUID id = UUID.randomUUID();
        seeder.deleteDemoAgent("demo_x", null);
        verify(agentService, never()).delete(any(UUID.class));

        seeder.deleteDemoAgent("demo_x", id);
        verify(agentService).delete(id);

        doThrow(new IllegalStateException("Premium feature")).when(agentService).delete(id);
        seeder.deleteDemoAgent("demo_x", id);
    }
}
