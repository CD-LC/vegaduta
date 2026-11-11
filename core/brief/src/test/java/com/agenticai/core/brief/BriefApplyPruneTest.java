package com.agenticai.core.brief;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.brief.BriefApplyLedger.Binding;
import com.agenticai.core.brief.model.Brief;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

/**
 * What a prune removes, in which order, under which permission, and how a failed removal is left
 * behind.
 */
class BriefApplyPruneTest {

    private static final UUID BRIEF = UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");
    private static final UUID JOB = UUID.fromString("99999999-bbbb-cccc-dddd-eeeeeeeeeeee");
    private static final UUID STEP = UUID.fromString("77777777-bbbb-cccc-dddd-eeeeeeeeeeee");

    private EnvoyPorts ports;
    private BriefApplyLedger ledger;
    private BriefApplyPrune prune;
    private ApplyCtx ctx;

    @BeforeEach
    void setUp() {
        ports = mock(EnvoyPorts.class);
        ledger = mock(BriefApplyLedger.class);
        prune = new BriefApplyPrune(ports, ledger);
        when(ledger.begin(eq(BRIEF), anyInt(), eq(JOB), anyInt(), anyString(), anyString()))
                .thenReturn(STEP);
        ctx = context(BriefFixtures.valid().build(), null);
        ctx.canIntegrate = true;
        ctx.canTools = true;
        ctx.canChannels = true;
    }

    private static ApplyCtx context(Brief brief, Brief previous) {
        return new ApplyCtx(BRIEF, 3, JOB, brief, previous, TrustRung.UNDER_WATCH);
    }

    @Test
    void everyUndeclaredBindingIsRemovedInReverseDependencyOrder() {
        ctx.bindings.put("k1", new Binding("KNOWLEDGE", "kid"));
        ctx.bindings.put("a1", new Binding("AGENT", "aid"));
        ctx.bindings.put("w1", new Binding("WORKFLOW", "wid"));
        ctx.bindings.put("p1", new Binding("PROCEDURE", "pid"));
        ctx.bindings.put("m1", new Binding("MCP", "mid"));
        ctx.bindings.put("t1", new Binding("TOOL_OPENAPI", "tid"));
        ctx.bindings.put("t2", new Binding("TOOL_CODE", "tcid"));
        ctx.bindings.put("e1", new Binding("EVAL_DATASET", "did"));

        prune.pruneOrphans(ctx, Set.of());

        InOrder order = inOrder(ports);
        order.verify(ports).deleteEvalDataset("did");
        order.verify(ports).deleteWorkflow("wid");
        order.verify(ports).deleteAgent("aid");
        order.verify(ports).deleteMcp("mid");
        order.verify(ports).deleteTool("TOOL_OPENAPI", "tid");
        order.verify(ports).deleteTool("TOOL_CODE", "tcid");
        order.verify(ports).deleteProcedure("pid");
        order.verify(ports).deleteKnowledge("kid");
        assertThat(ctx.bindings).isEmpty();
        verify(ledger).unbind(BRIEF, "a1");
        verify(ledger, org.mockito.Mockito.times(8)).done(eq(STEP), anyString(), any());
    }

    @Test
    void declaredBindingsAreLeftAlone() {
        ctx.bindings.put("a1", new Binding("AGENT", "aid"));
        ctx.bindings.put("a2", new Binding("AGENT", "aid2"));

        prune.pruneOrphans(ctx, Set.of("a1"));

        verify(ports, never()).deleteAgent("aid");
        verify(ports).deleteAgent("aid2");
        assertThat(ctx.bindings).containsOnlyKeys("a1");
    }

    @Test
    void aFailedRemovalStaysBoundAndDoesNotStopTheRest() {
        ctx.bindings.put("a1", new Binding("AGENT", "aid"));
        ctx.bindings.put("k1", new Binding("KNOWLEDGE", "kid"));
        doThrow(new IllegalStateException("agent busy")).when(ports).deleteAgent("aid");

        prune.pruneOrphans(ctx, Set.of());

        verify(ledger).failed(STEP, "agent busy");
        verify(ports).deleteKnowledge("kid");
        assertThat(ctx.bindings).containsOnlyKeys("a1");
        verify(ledger, never()).unbind(BRIEF, "a1");
    }

    @Test
    void removingAMcpServerNeedsTheIntegrationsPermission() {
        ctx.canIntegrate = false;
        ctx.bindings.put("m1", new Binding("MCP", "mid"));

        prune.pruneOrphans(ctx, Set.of());

        verify(ports, never()).deleteMcp(anyString());
        verify(ledger).failed(eq(STEP), contains("Manage integrations"));
        assertThat(ctx.bindings).containsOnlyKeys("m1");
    }

    @Test
    void removingAToolNeedsBothToolAndIntegrationPermissions() {
        ctx.canTools = false;
        ctx.bindings.put("t1", new Binding("TOOL_OPENAPI", "tid"));

        prune.pruneOrphans(ctx, Set.of());

        verify(ports, never()).deleteTool(anyString(), anyString());
        verify(ledger).failed(eq(STEP), contains("Manage tools and Manage integrations"));
    }

    @Test
    void removingAChannelNeedsTheChannelsPermission() {
        ctx.canChannels = false;
        ctx.bindings.put("c1", new Binding("CHANNEL", "WHATSAPP"));

        prune.pruneOrphans(ctx, Set.of());

        verify(ports, never()).unbindChannel(anyString(), anyString());
        verify(ledger).failed(eq(STEP), contains("Manage channels"));
    }

    @Test
    void aChannelIsUnboundFromTheAgentItsDeclaringBriefRoutedTo() {
        Brief declaring = channelBrief();
        ctx = context(null, declaring);
        ctx.canChannels = true;
        ctx.bindings.put("a1", new Binding("AGENT", "aid"));
        ctx.bindings.put("c1", new Binding("CHANNEL", "WHATSAPP"));

        prune.pruneOrphans(ctx, Set.of("a1"));

        verify(ports).unbindChannel("WHATSAPP", "aid");
        assertThat(ctx.bindings).containsOnlyKeys("a1");
    }

    @Test
    void aChannelWhoseAgentIsUnknownIsDroppedWithoutCallingThePort() {
        ctx.bindings.put("c9", new Binding("CHANNEL", "SMS"));

        prune.pruneOrphans(ctx, Set.of());

        verify(ports, never()).unbindChannel(anyString(), anyString());
        verify(ledger).unbind(BRIEF, "c9");
        assertThat(ctx.bindings).isEmpty();
    }

    @Test
    void bindingsOfAnUnvisitedKindAreIgnored() {
        ctx.bindings.put("x1", new Binding("MYSTERY", "xid"));
        // only the fixed kinds are visited, so an unknown kind is never touched
        prune.pruneOrphans(ctx, Set.of());

        verify(ledger, never()).begin(any(), anyInt(), any(), anyInt(), anyString(), eq("x1"));
        assertThat(ctx.bindings).containsOnlyKeys("x1");
    }

    private static Brief channelBrief() {
        BriefFixtures.B b = BriefFixtures.valid();
        b.channels =
                new java.util.ArrayList<>(
                        List.of(new Brief.ChannelSpec("c1", "WHATSAPP", "a1", null)));
        return b.build();
    }
}
