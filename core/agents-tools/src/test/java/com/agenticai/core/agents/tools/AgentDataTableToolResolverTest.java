package com.agenticai.core.agents.tools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.agenticai.core.agents.core.AgentService;
import com.agenticai.core.agents.tools.classification.BuiltInToolClassifications;
import com.agenticai.core.agents.tools.classification.ToolClassification;
import com.agenticai.core.agents.tools.classification.ToolDeterminism;
import com.agenticai.core.agents.tools.classification.ToolSensitivity;
import com.agenticai.core.agents.tools.classification.ToolTrustTier;
import com.agenticai.core.tables.DataTableService;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.ObjectProvider;

/**
 * data_table_query / data_table_write are offered to a chat turn only when the flag is on AND the
 * agent named the tool in its allow-list; the admin tool picker lists both whenever the flag is on.
 */
class AgentDataTableToolResolverTest {

    private AgentDataTableToolResolver resolver;
    private AgentService agentService;

    @BeforeEach
    void setUp() {
        AgentToolGuard toolGuard = mock(AgentToolGuard.class);
        when(toolGuard.guardAndLog(any(), any(), any(), any(), any(), any(), anyBoolean()))
                .thenAnswer(inv -> inv.getArgument(0));
        agentService = mock(AgentService.class);
        resolver = new AgentDataTableToolResolver(toolGuard, agentService);
    }

    @SuppressWarnings("unchecked")
    private static ObjectProvider<DataTableService> providing(DataTableService svc) {
        ObjectProvider<DataTableService> p = mock(ObjectProvider.class);
        when(p.getIfAvailable()).thenReturn(svc);
        return p;
    }

    private static DataTableService service(boolean enabled) {
        DataTableService svc = mock(DataTableService.class);
        when(svc.isEnabled()).thenReturn(enabled);
        return svc;
    }

    private List<String> turnTools() {
        return names(
                resolver.resolveDataTableTools(
                        UUID.randomUUID(), "s1", new AtomicInteger(), null, null, false));
    }

    private List<String> discoveryTools() {
        return names(
                resolver.resolveDataTableToolsForDiscovery(
                        UUID.randomUUID(), "probe", new AtomicInteger(), null, null, false));
    }

    private static List<String> names(List<ToolCallback> tools) {
        return tools.stream().map(t -> t.getToolDefinition().name()).toList();
    }

    @Test
    void nothingIsOfferedWhenNeverWiredOrTheBeanIsAbsent() {
        when(agentService.getAllowedToolNames(any()))
                .thenReturn(Set.of("data_table_query", "data_table_write"));
        assertThat(turnTools()).isEmpty();
        assertThat(discoveryTools()).isEmpty();

        resolver.setDataTables(providing(null));
        assertThat(turnTools()).isEmpty();
        assertThat(discoveryTools()).isEmpty();
    }

    @Test
    void withTheFlagOffNothingIsOfferedEvenToAnOptedInAgent() {
        when(agentService.getAllowedToolNames(any()))
                .thenReturn(Set.of("data_table_query", "data_table_write"));
        resolver.setDataTables(providing(service(false)));

        assertThat(turnTools()).isEmpty();
        assertThat(discoveryTools()).isEmpty();
    }

    @Test
    void anUnrestrictedAgentIsNotOptedIn() {
        resolver.setDataTables(providing(service(true)));
        when(agentService.getAllowedToolNames(any())).thenReturn(Set.of());
        assertThat(turnTools()).isEmpty();

        when(agentService.getAllowedToolNames(any())).thenReturn(null);
        assertThat(turnTools()).isEmpty();

        when(agentService.getAllowedToolNames(any())).thenThrow(new IllegalStateException("gone"));
        assertThat(turnTools()).isEmpty();
    }

    @Test
    void readAndWriteOptInSeparately() {
        resolver.setDataTables(providing(service(true)));

        when(agentService.getAllowedToolNames(any())).thenReturn(Set.of("data_table_query"));
        assertThat(turnTools()).containsExactly("data_table_query");

        when(agentService.getAllowedToolNames(any()))
                .thenReturn(Set.of("data_table_write", "fetch_web_page"));
        assertThat(turnTools()).containsExactly("data_table_write");

        when(agentService.getAllowedToolNames(any()))
                .thenReturn(Set.of("data_table_query", "data_table_write"));
        assertThat(turnTools()).containsExactly("data_table_query", "data_table_write");
    }

    @Test
    void theAdminPickerListsBothToolsWheneverTheFlagIsOn() {
        resolver.setDataTables(providing(service(true)));
        when(agentService.getAllowedToolNames(any())).thenReturn(Set.of());

        assertThat(discoveryTools()).containsExactly("data_table_query", "data_table_write");
    }

    @Test
    void bothToolsAreClassifiedFailClosedUnderTheNamesTheCallbacksUse() {
        ToolClassification read = BuiltInToolClassifications.all().get("data_table_query");
        ToolClassification write = BuiltInToolClassifications.all().get("data_table_write");

        assertThat(read)
                .as("data_table_query must be classified in the same commit as the tool")
                .isNotNull();
        assertThat(write)
                .as("data_table_write must be classified in the same commit as the tool")
                .isNotNull();
        for (ToolClassification c : List.of(read, write)) {
            assertThat(c.needsToolContext()).isTrue();
            assertThat(c.determinism()).isNotEqualTo(ToolDeterminism.PURE);
            assertThat(c.sensitivity()).isNotEqualTo(ToolSensitivity.EGRESS);
            assertThat(c.requiresHumanConfirmation()).isFalse();
        }
        // stored cells can carry text an earlier run copied from the outside, so a read is tainted
        assertThat(read.trust()).isEqualTo(ToolTrustTier.UNTRUSTED_EXTERNAL);
        assertThat(read.sensitivity()).isEqualTo(ToolSensitivity.READ_TENANT);
        // the write is a tenant-scoped DURABLE side effect and a taint sink
        assertThat(write.sensitivity()).isEqualTo(ToolSensitivity.WRITE_TENANT);
        assertThat(write.determinism()).isEqualTo(ToolDeterminism.DURABLE);
        assertThat(write.irreversible()).isFalse();
    }
}
