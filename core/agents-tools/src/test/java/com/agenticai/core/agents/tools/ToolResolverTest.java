package com.agenticai.core.agents.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.agents.core.AgentService;
import com.agenticai.core.audit.IssueSeverity;
import com.agenticai.core.audit.IssueSource;
import com.agenticai.core.audit.PlatformIssueService;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.beans.factory.ObjectProvider;

/**
 * Pins the merge / filter / collision / cap semantics {@code AgentToolProvider.resolveToolsFor} had
 * before it became a composite of {@link ToolSource}s, now exercised through {@link ToolResolver}
 * with fake sources. The pure helpers it delegates to ({@code mergeFavoringBuiltins}, {@code
 * capToHardLimit}) have their own cases in {@link AgentToolProviderCapTest}.
 */
class ToolResolverTest {

    private final UUID agentId = UUID.randomUUID();
    private AgentService agentService;
    private ToolRelevanceSelector selector;
    private PlatformIssueService issues;
    private int hardCap;

    @BeforeEach
    void setUp() {
        agentService = mock(AgentService.class);
        when(agentService.getAllowedToolNames(agentId)).thenReturn(Set.of());
        selector = mock(ToolRelevanceSelector.class);
        when(selector.select(any(), any(), any(), any()))
                .thenAnswer(inv -> inv.<List<ToolCallback>>getArgument(1));
        issues = mock(PlatformIssueService.class);
        hardCap = 40;
    }

    private static ToolCallback tool(String name) {
        return new ToolCallback() {
            @Override
            public ToolDefinition getToolDefinition() {
                return ToolDefinition.builder()
                        .name(name)
                        .description("test tool " + name)
                        .inputSchema("{}")
                        .build();
            }

            @Override
            public String call(String toolInput) {
                return name;
            }
        };
    }

    private static ToolSource source(String id, ToolSource.Kind kind, String... names) {
        return ToolSource.of(
                id, kind, ctx -> java.util.Arrays.stream(names).map(ToolResolverTest::tool).toList());
    }

    private ToolResolver resolver(ToolSource... sources) {
        @SuppressWarnings("unchecked")
        ObjectProvider<PlatformIssueService> issueProvider = mock(ObjectProvider.class);
        when(issueProvider.getIfAvailable()).thenReturn(issues);
        return new ToolResolver(
                List.of(sources),
                agentService,
                selector,
                mock(AgentToolGuard.class),
                mock(AgentIntegrationToolResolver.class),
                issueProvider,
                new ToolSearchDeferral(),
                hardCap);
    }

    private ToolResolutionContext ctx() {
        return ctx(true, true);
    }

    private ToolResolutionContext ctx(boolean allowDelegation, boolean mainChatTurn) {
        return new ToolResolutionContext(
                agentId,
                "s1",
                "subject",
                new AtomicInteger(0),
                "hello",
                null,
                false,
                allowDelegation,
                mainChatTurn,
                List.of());
    }

    private static List<String> names(List<ToolCallback> tools) {
        return tools.stream().map(t -> t.getToolDefinition().name()).toList();
    }

    @Test
    void builtInsKeepSourceOrderAndMcpToolsComeAfterThem() {
        ToolResolver resolver =
                resolver(
                        source("mcp", ToolSource.Kind.MCP, "mcp__s__a"),
                        source("one", ToolSource.Kind.BUILT_IN, "b1", "b2"),
                        source("two", ToolSource.Kind.BUILT_IN, "b3"));

        ResolvedToolList result = resolver.resolve(ctx(), true);

        assertEquals(List.of("b1", "b2", "b3", "mcp__s__a"), names(result));
        assertTrue(result.anyMcpToolsOffered());
    }

    @Test
    void aStableToolSetTurnOffersEveryAllowedToolInTheSameOrderWithoutAskingTheSelector() {
        // A selector that would keep only one tool: a stable-tool-set turn must not consult it.
        when(selector.select(any(), any(), any(), any()))
                .thenAnswer(inv -> inv.<List<ToolCallback>>getArgument(1).subList(0, 1));
        ToolResolver resolver =
                resolver(
                        source("one", ToolSource.Kind.BUILT_IN, "b1", "b2"),
                        source("mcp", ToolSource.Kind.MCP, "mcp__s__a"));

        List<String> first = names(resolver.resolve(ctx(), true, true));
        List<String> second = names(resolver.resolve(ctx(), true, true));

        assertEquals(List.of("b1", "b2", "mcp__s__a"), first);
        assertEquals(first, second, "the same tools in the same order on every turn");
        org.mockito.Mockito.verify(selector, org.mockito.Mockito.never()).select(any(), any(), any(), any());
        assertEquals(List.of("b1"), names(resolver.resolve(ctx(), true, false)), "a normal turn still selects");
    }

    @Test
    void aStableToolSetTurnStillHonoursTheHardCap() {
        hardCap = 2;
        ToolResolver resolver = resolver(source("one", ToolSource.Kind.BUILT_IN, "b1", "b2", "b3"));

        assertEquals(2, resolver.resolve(ctx(), true, true).size());
    }

    @Test
    void anyMcpToolsOfferedIsFalseWithoutMcpCandidates() {
        ResolvedToolList result =
                resolver(source("one", ToolSource.Kind.BUILT_IN, "b1")).resolve(ctx(), true);

        assertFalse(result.anyMcpToolsOffered());
    }

    @Test
    void mcpToolCollidingWithABuiltInIsDroppedAndRecordedOnTheIssuesPortal() {
        ToolResolver resolver =
                resolver(
                        source("mcp", ToolSource.Kind.MCP, "search_code", "mcp__s__other"),
                        source("builtin", ToolSource.Kind.BUILT_IN, "search_code"));

        ResolvedToolList result = resolver.resolve(ctx(), true);

        assertEquals(List.of("search_code", "mcp__s__other"), names(result));
        verify(issues)
                .record(
                        eq(IssueSource.TOOL_CALL),
                        eq("search_code"),
                        eq(IssueSeverity.WARNING),
                        any(),
                        any());
    }

    @Test
    void aFailingIssueRecorderNeverFailsTheResolution() {
        org.mockito.Mockito.doThrow(new IllegalStateException("db down"))
                .when(issues)
                .record(any(), any(), any(), any(), any());
        ToolResolver resolver =
                resolver(
                        source("mcp", ToolSource.Kind.MCP, "dup"),
                        source("builtin", ToolSource.Kind.BUILT_IN, "dup"));

        assertEquals(List.of("dup"), names(resolver.resolve(ctx(), true)));
    }

    @Test
    void postAllowListSourceReplacesSameNamedEarlierTool() {
        when(agentService.getAllowedToolNames(agentId)).thenReturn(Set.of());
        ToolResolver resolver =
                resolver(
                        source("builtin", ToolSource.Kind.BUILT_IN, "load_skill", "keep"),
                        ToolSource.of(
                                "skills",
                                ToolSource.Kind.POST_ALLOW_LIST,
                                ctx -> List.of(tool("load_skill"))));

        assertEquals(
                List.of("keep", "load_skill"), names(resolver.resolve(ctx(), true)));
    }

    @Test
    void allowListFiltersCandidatesButNotPostAllowListSources() {
        when(agentService.getAllowedToolNames(agentId)).thenReturn(Set.of("keep"));
        ToolResolver resolver =
                resolver(
                        source("builtin", ToolSource.Kind.BUILT_IN, "keep", "drop"),
                        source("mcp", ToolSource.Kind.MCP, "mcp__s__drop"),
                        source("skills", ToolSource.Kind.POST_ALLOW_LIST, "load_skill"));

        ResolvedToolList result = resolver.resolve(ctx(), true);

        assertEquals(List.of("keep", "load_skill"), names(result));
    }

    @Test
    void postAllowListSourceSeesTheFilteredListAndLaterOnesSeeEarlierAdditions() {
        when(agentService.getAllowedToolNames(agentId)).thenReturn(Set.of("keep"));
        List<List<String>> seen = new ArrayList<>();
        Function<String, ToolSource> post =
                id ->
                        ToolSource.of(
                                id,
                                ToolSource.Kind.POST_ALLOW_LIST,
                                c -> {
                                    seen.add(names(c.resolvedSoFar()));
                                    return List.of(tool(id));
                                });
        ToolResolver resolver =
                resolver(
                        source("builtin", ToolSource.Kind.BUILT_IN, "keep", "drop"),
                        post.apply("first"),
                        post.apply("second"));

        resolver.resolve(ctx(), true);

        assertEquals(List.of(List.of("keep"), List.of("keep", "first")), seen);
    }

    @Test
    void aSourceThatDoesNotApplyIsNotCalled() {
        ToolSource skipped =
                ToolSource.of(
                        "restore",
                        ToolSource.Kind.POST_ALLOW_LIST,
                        c -> c.mainChatTurn() && c.chatClient() != null,
                        c -> List.of(tool("restore_tool_result")));
        ToolResolver resolver = resolver(skipped);

        assertTrue(resolver.resolve(ctx(true, true), true).isEmpty(), "no chat client");
        assertTrue(resolver.resolve(ctx(true, false), true).isEmpty(), "not a main turn");
    }

    @Test
    void theDelegationFlagReachesEverySource() {
        List<Boolean> flags = new ArrayList<>();
        ToolSource probe =
                ToolSource.of(
                        "probe",
                        ToolSource.Kind.BUILT_IN,
                        c -> {
                            flags.add(c.allowDelegation());
                            return List.of();
                        });
        ToolResolver resolver = resolver(probe);

        resolver.resolve(ctx(false, true), true);
        resolver.resolve(ctx(true, true), true);

        assertEquals(List.of(false, true), flags);
    }

    @Test
    void withoutRelevanceSelectionTheUntrimmedCandidatesComeBackAndTheSelectorIsSkipped() {
        hardCap = 2;
        ToolResolver resolver =
                resolver(source("builtin", ToolSource.Kind.BUILT_IN, "a", "b", "c", "d"));

        ResolvedToolList result = resolver.resolve(ctx(), false);

        assertEquals(List.of("a", "b", "c", "d"), names(result));
        verify(selector, never()).select(any(), any(), any(), any());
    }

    @Test
    void theHardCapTruncatesAfterTheSelector() {
        hardCap = 2;
        ToolResolver resolver =
                resolver(source("builtin", ToolSource.Kind.BUILT_IN, "a", "b", "c", "d"));

        ResolvedToolList result = resolver.resolve(ctx(), true);

        assertEquals(List.of("a", "b"), names(result));
    }

    @Test
    void theHardCapKeepsAnMcpFamilyWholeOrDropsItWhole() {
        hardCap = 2;
        ToolResolver resolver =
                resolver(
                        source("builtin", ToolSource.Kind.BUILT_IN, "solo"),
                        source("mcp", ToolSource.Kind.MCP, "mcp__p__nav", "mcp__p__shot"));

        ResolvedToolList result = resolver.resolve(ctx(), true);

        assertEquals(List.of("solo"), names(result));
    }

    @Test
    void mcpCandidatesAreTheMcpKindOnly() {
        ToolResolver resolver =
                resolver(
                        source("builtin", ToolSource.Kind.BUILT_IN, "b"),
                        source("mcp", ToolSource.Kind.MCP, "mcp__s__a"));

        assertEquals(List.of("mcp__s__a"), names(resolver.mcpCandidates(ctx())));
    }

    @Test
    void theSelectorReceivesTheMergedListAndTheMcpNames() {
        ToolResolver resolver =
                resolver(
                        source("builtin", ToolSource.Kind.BUILT_IN, "b"),
                        source("mcp", ToolSource.Kind.MCP, "mcp__s__a"));

        resolver.resolve(ctx(), true);

        verify(selector)
                .select(
                        eq("hello"),
                        any(),
                        eq(AgentToolSelection.ALWAYS_RELEVANT_TOOL_NAMES),
                        eq(Set.of("mcp__s__a")));
    }

    // ------------------------------------------- the continuation after an approval keeps earlier tools

    private ToolResolutionContext stickyCtx(Set<String> sticky) {
        return new ToolResolutionContext(
                agentId,
                "s1",
                "subject",
                new AtomicInteger(0),
                "hello",
                null,
                false,
                true,
                true,
                List.of(),
                sticky);
    }

    @Test
    void selectionNeverTrimsTheStandingCapabilitiesAndNothingElseOnAnOrdinaryTurn() {
        org.mockito.ArgumentCaptor<Set<String>> always = org.mockito.ArgumentCaptor.forClass(Set.class);

        resolver(source("one", ToolSource.Kind.BUILT_IN, "b1")).resolve(ctx(), true);

        verify(selector).select(any(), any(), always.capture(), any());
        assertEquals(AgentToolSelection.ALWAYS_RELEVANT_TOOL_NAMES, always.getValue());
    }

    @Test
    void theToolsAConversationAlreadyUsedStayOnOfferOnItsContinuationTurn() {
        org.mockito.ArgumentCaptor<Set<String>> always = org.mockito.ArgumentCaptor.forClass(Set.class);

        resolver(source("one", ToolSource.Kind.BUILT_IN, "b1", "lookup_customer"))
                .resolve(stickyCtx(Set.of("lookup_customer", "send_invoice")), true);

        verify(selector).select(any(), any(), always.capture(), any());
        assertTrue(always.getValue().containsAll(AgentToolSelection.ALWAYS_RELEVANT_TOOL_NAMES));
        assertTrue(always.getValue().containsAll(Set.of("lookup_customer", "send_invoice")));
    }

    @Test
    void stickyToolsDefaultToNoneAndAreImmutable() {
        assertTrue(ctx().stickyTools().isEmpty());
        assertTrue(stickyCtx(null).stickyTools().isEmpty());
        Set<String> mutable = new java.util.HashSet<>(Set.of("a"));
        ToolResolutionContext c = stickyCtx(mutable);
        mutable.add("b");
        assertEquals(Set.of("a"), c.stickyTools());
        assertEquals(Set.of("a"), c.withResolved(List.of()).stickyTools());
    }
}
