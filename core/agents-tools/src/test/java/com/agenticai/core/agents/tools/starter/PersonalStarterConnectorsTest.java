package com.agenticai.core.agents.tools.starter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.agents.tools.McpStdioSidecarProperties;
import com.agenticai.core.agents.tools.classification.ToolClassification;
import com.agenticai.core.agents.tools.classification.ToolClassificationEntity;
import com.agenticai.core.agents.tools.classification.ToolClassificationRegistry;
import com.agenticai.core.agents.tools.classification.ToolSensitivity;
import com.agenticai.core.agents.tools.classification.ToolTrustTier;
import com.agenticai.core.mcp.McpStarterService;
import com.agenticai.core.tenancy.TenantSchemaIterator;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class PersonalStarterConnectorsTest {

    private TenantSchemaIterator tenants;
    private McpStarterService starters;
    private ToolClassificationRegistry classifications;
    private McpStdioSidecarProperties sidecar;
    private PersonalStarterConnectors connectors;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        tenants = mock(TenantSchemaIterator.class);
        starters = mock(McpStarterService.class);
        classifications = mock(ToolClassificationRegistry.class);
        sidecar = new McpStdioSidecarProperties();
        connectors = new PersonalStarterConnectors(tenants, starters, classifications, sidecar);
        doAnswer(inv -> {
                    ((Consumer<String>) inv.getArgument(0)).accept("tenant_personal");
                    return null;
                })
                .when(tenants)
                .forEachActiveTenantSchema(any());
    }

    private static ToolClassificationEntity stored(String toolName) {
        ToolClassificationEntity e = mock(ToolClassificationEntity.class);
        when(e.getToolName()).thenReturn(toolName);
        return e;
    }

    @Test
    void withoutTheRunnerNothingIsRegistered() {
        sidecar.setEnabled(false);

        connectors.seedOnStartup();

        verify(tenants, never()).forEachActiveTenantSchema(any());
        verify(starters, never()).seedMissing();
    }

    @Test
    void withTheRunnerEveryTenantIsSeeded() {
        sidecar.setEnabled(true);
        when(starters.seedMissing()).thenReturn(List.of());

        connectors.seedOnStartup();

        verify(starters).seedMissing();
        verify(classifications, never()).declare(any(), any(), any(), any());
    }

    @Test
    void aNewlyRegisteredStarterIsClassifiedUnlessTheTenantAlreadyHoldsThatRow() {
        sidecar.setEnabled(true);
        when(starters.seedMissing())
                .thenReturn(
                        List.of(
                                new McpStarterService.Seeded(UUID.randomUUID(), "fetch", true),
                                new McpStarterService.Seeded(UUID.randomUUID(), "time", true)));
        // The owner already tightened fetch by hand: that row stays.
        ToolClassificationEntity tightened = stored("mcp__fetch__*");
        when(classifications.storedClassifications()).thenReturn(List.of(tightened));

        connectors.seedOnStartup();

        ArgumentCaptor<ToolClassification> declared = ArgumentCaptor.forClass(ToolClassification.class);
        verify(classifications).declare(declared.capture(), eq("MCP_IMPORT"), any(), any());
        assertThat(declared.getValue().toolName()).isEqualTo("mcp__time__*");
    }

    @Test
    void aFailedClassificationDoesNotStopTheOthersOrTheStart() {
        sidecar.setEnabled(true);
        when(starters.seedMissing())
                .thenReturn(
                        List.of(
                                new McpStarterService.Seeded(UUID.randomUUID(), "fetch", true),
                                new McpStarterService.Seeded(UUID.randomUUID(), "time", true)));
        when(classifications.storedClassifications()).thenReturn(List.of());
        doAnswer(inv -> {
                    throw new IllegalStateException("db down");
                })
                .when(classifications)
                .declare(any(), any(), any(), any());

        connectors.seedOnStartup();

        verify(classifications, org.mockito.Mockito.times(2)).declare(any(), any(), any(), any());
    }

    @Test
    void aClassificationThatFailedOnAnEarlierStartIsRetriedOnTheNextOne() {
        sidecar.setEnabled(true);
        // Second start: nothing is newly registered, but fetch is still our registration and unclassified.
        when(starters.seedMissing()).thenReturn(List.of());
        when(starters.registeredStarterNames()).thenReturn(List.of("fetch", "time"));
        ToolClassificationEntity timeRow = stored("mcp__time__*");
        when(classifications.storedClassifications()).thenReturn(List.of(timeRow));

        connectors.seedOnStartup();

        ArgumentCaptor<ToolClassification> declared = ArgumentCaptor.forClass(ToolClassification.class);
        verify(classifications).declare(declared.capture(), eq("MCP_IMPORT"), any(), any());
        assertThat(declared.getValue().toolName()).isEqualTo("mcp__fetch__*");
    }

    @Test
    void seedingRunsBeforeTheStartupReconnectSoItsEnabledRowsAreConnectedByIt() throws Exception {
        // DynamicMcpConnectionManager.reconnectEnabledOnStartup listens to the same event with the default
        // (lowest) order and connects every enabled dynamic row it finds. This class registers rows and
        // never connects them itself, so it must run first.
        java.lang.reflect.Method seed = PersonalStarterConnectors.class.getMethod("seedOnStartup");
        org.springframework.core.annotation.Order order =
                seed.getAnnotation(org.springframework.core.annotation.Order.class);
        assertThat(order).isNotNull();
        assertThat(order.value()).isLessThan(org.springframework.core.Ordered.LOWEST_PRECEDENCE);

        java.lang.reflect.Method reconnect =
                Class.forName("com.agenticai.core.agents.tools.DynamicMcpSchedulers")
                        .getDeclaredMethod("reconnectEnabledOnStartup");
        assertThat(reconnect.getAnnotation(org.springframework.core.annotation.Order.class)).isNull();
        assertThat(reconnect.getAnnotation(org.springframework.context.event.EventListener.class)).isNotNull();
    }

    @Test
    void everyStarterHasAClassificationAndItIsReadOnlyThirdPartyContent() {
        for (String name : McpStarterService.starterNames()) {
            ToolClassification c = PersonalStarterConnectors.CLASSIFICATIONS.get(name);
            assertThat(c).as("classification for starter %s", name).isNotNull();
            assertThat(c.toolName()).isEqualTo("mcp__" + name + "__*");
            // Third-party bytes taint the turn; none of these can write, so none is a taint sink.
            assertThat(c.trust()).isEqualTo(ToolTrustTier.UNTRUSTED_EXTERNAL);
            assertThat(c.sensitivity()).isEqualTo(ToolSensitivity.READ_PUBLIC);
            assertThat(c.isTaintSink()).isFalse();
            assertThat(c.irreversible()).isFalse();
        }
        assertThat(PersonalStarterConnectors.CLASSIFICATIONS.keySet())
                .containsExactlyInAnyOrderElementsOf(McpStarterService.starterNames());
    }
}
