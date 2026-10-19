package com.agenticai.core.agents.tools.mcpapps;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.agenticai.core.agents.tools.McpAppStepHook;
import com.agenticai.core.security.TenantContext;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class McpAppsServiceTest {

    private static final String TENANT = "t_acme";
    private static final String USER = "alice";
    private static final String URI = "ui://weather/forecast";
    private static final UUID REG = UUID.randomUUID();
    private static final UUID OTHER_REG = UUID.randomUUID();
    private static final UUID AGENT = UUID.randomUUID();

    private McpSyncClient client;
    private McpAppsService svc;

    private static McpAppUiMeta.Declaration decl(String uri) {
        return new McpAppUiMeta.Declaration(uri, McpAppUiMeta.DeclaredCsp.NONE);
    }

    private static McpSchema.ReadResourceResult html(String uri, String mime, String body) {
        return new McpSchema.ReadResourceResult(
                List.of(new McpSchema.TextResourceContents(uri, mime, body)));
    }

    private McpAppsService service(McpAppsConfig cfg, McpSyncClient c) {
        return new McpAppsService(
                cfg, (tenant, id) -> TENANT.equals(tenant) && REG.equals(id) ? c : null);
    }

    private void register(McpAppsService s) {
        s.recordServer(
                TENANT,
                REG,
                "weather",
                List.of(
                        new McpAppsService.ToolEntry(
                                "forecast", "mcp__weather__forecast", decl(URI)),
                        new McpAppsService.ToolEntry("refresh", "mcp__weather__refresh", null)));
        s.recordServer(
                TENANT,
                OTHER_REG,
                "mail",
                List.of(new McpAppsService.ToolEntry("send", "mcp__mail__send", null)));
    }

    private static void as(String tenant, String user) {
        TenantContext.set(TenantContext.of(tenant, user, Set.of()));
    }

    private Map<String, Object> mint(McpAppsService s) {
        McpAppStepHook hook = s.hookFor(TenantContext.of(TENANT, USER, Set.of()), AGENT, "sess");
        assertNotNull(hook);
        return hook.onToolResult(
                "mcp__weather__forecast",
                "{\"city\":\"Pune\",\"apiKey\":\"sk-abcdefghijklmnopqrstuvwxyz\"}",
                "{\"temp\":31}");
    }

    @BeforeEach
    void setUp() {
        client = mock(McpSyncClient.class);
        svc = service(McpAppsConfig.defaults(), client);
        register(svc);
        as(TENANT, USER);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    // ---- fetch rules
    // --------------------------------------------------------------------------------

    @Test
    void aNonUiSchemeIsNeverFetchedAndNeverRecorded() {
        for (String bad :
                List.of(
                        "https://evil.example/app",
                        "http://127.0.0.1/x",
                        "file:///etc/passwd",
                        "data:text/html,<script>",
                        "javascript:alert(1)",
                        "ui:/missing-slash",
                        "UI://weather/x ",
                        "ui://",
                        "ui://a/../../b b")) {
            // a tool descriptor that points anywhere but ui:// yields no declaration at all ...
            assertTrue(
                    McpAppUiMeta.declarationOf(Map.of("ui", Map.of("resourceUri", bad))).isEmpty(),
                    bad);
            // ... and the fetch path refuses it even if something handed it over directly.
            McpAppsException e =
                    assertThrows(
                            McpAppsException.class,
                            () -> svc.fetchResource(TENANT, REG, bad, serverApps(svc)),
                            bad);
            assertEquals(McpAppsException.Code.REFUSED, e.code(), bad);
        }
        verifyNoInteractions(client);
    }

    @Test
    void theLegacyFlatKeyIsReadButStillMustBeUi() {
        assertEquals(
                URI,
                McpAppUiMeta.declarationOf(Map.of("ui/resourceUri", URI))
                        .orElseThrow()
                        .resourceUri());
        assertTrue(
                McpAppUiMeta.declarationOf(Map.of("ui/resourceUri", "https://x.example/"))
                        .isEmpty());
    }

    @Test
    void aResourceThatNoToolOfTheServerDeclaredIsRefusedWithoutTouchingTheServer() {
        McpAppsException e =
                assertThrows(
                        McpAppsException.class,
                        () ->
                                svc.fetchResource(
                                        TENANT, REG, "ui://weather/other", serverApps(svc)));
        assertEquals(McpAppsException.Code.REFUSED, e.code());
        verifyNoInteractions(client);
    }

    @Test
    void wrongMimeTypeIsRefused() {
        for (String mime :
                new String[] {
                    "text/html",
                    "application/javascript",
                    "text/html;profile=other",
                    "image/svg+xml",
                    null
                }) {
            when(client.readResource(any(McpSchema.ReadResourceRequest.class)))
                    .thenReturn(html(URI, mime, "<p>x</p>"));
            McpAppsException e =
                    assertThrows(
                            McpAppsException.class,
                            () -> svc.fetchResource(TENANT, REG, URI, serverApps(svc)),
                            String.valueOf(mime));
            assertEquals(McpAppsException.Code.REFUSED, e.code());
        }
    }

    @Test
    void theAppMimeIsAcceptedRegardlessOfCaseAndSpacing() {
        assertTrue(McpAppsService.isAppMime("text/html;profile=mcp-app"));
        assertTrue(McpAppsService.isAppMime("Text/HTML; profile=mcp-app"));
        assertTrue(McpAppsService.isAppMime("text/html; profile=\"mcp-app\""));
        assertFalse(McpAppsService.isAppMime("text/html"));
        assertFalse(McpAppsService.isAppMime("text/html;profile=mcp-app;charset=utf-8x"));
    }

    @Test
    void anOversizeAppIsRefusedNotTruncated() {
        McpAppsService small =
                service(
                        new McpAppsConfig(100, null, null, List.of(), true, 0, null, 0, 0, 0, 0),
                        client);
        register(small);
        when(client.readResource(any(McpSchema.ReadResourceRequest.class)))
                .thenReturn(html(URI, "text/html;profile=mcp-app", "x".repeat(101)));
        McpAppsException e =
                assertThrows(
                        McpAppsException.class,
                        () -> small.fetchResource(TENANT, REG, URI, serverApps(small)));
        assertEquals(McpAppsException.Code.TOO_LARGE, e.code());
    }

    @Test
    void sizeIsMeasuredInUtf8BytesNotChars() {
        McpAppsService small =
                service(
                        new McpAppsConfig(100, null, null, List.of(), true, 0, null, 0, 0, 0, 0),
                        client);
        register(small);
        // 60 chars but 180 UTF-8 bytes.
        when(client.readResource(any(McpSchema.ReadResourceRequest.class)))
                .thenReturn(html(URI, "text/html;profile=mcp-app", "€".repeat(60)));
        assertEquals(
                McpAppsException.Code.TOO_LARGE,
                assertThrows(
                                McpAppsException.class,
                                () -> small.fetchResource(TENANT, REG, URI, serverApps(small)))
                        .code());
    }

    @Test
    void binaryContentsAndAMismatchedResponseUriAreRefused() {
        when(client.readResource(any(McpSchema.ReadResourceRequest.class)))
                .thenReturn(
                        new McpSchema.ReadResourceResult(
                                List.of(
                                        new McpSchema.BlobResourceContents(
                                                URI, "text/html;profile=mcp-app", "PGI+"))));
        assertEquals(
                McpAppsException.Code.REFUSED,
                assertThrows(
                                McpAppsException.class,
                                () -> svc.fetchResource(TENANT, REG, URI, serverApps(svc)))
                        .code());

        when(client.readResource(any(McpSchema.ReadResourceRequest.class)))
                .thenReturn(
                        html(
                                "ui://weather/SOMETHING-ELSE",
                                "text/html;profile=mcp-app",
                                "<p>x</p>"));
        assertEquals(
                McpAppsException.Code.UNAVAILABLE,
                assertThrows(
                                McpAppsException.class,
                                () -> svc.fetchResource(TENANT, REG, URI, serverApps(svc)))
                        .code());
    }

    @Test
    void aServerTheTenantDidNotConnectIsRefusedAndNeverFetched() {
        // The registry knows the server, but no live client exists for (tenant, registration):
        // refuse, no fallback.
        McpAppsService nobody = new McpAppsService(McpAppsConfig.defaults(), (t, id) -> null);
        register(nobody);
        McpAppsException e =
                assertThrows(
                        McpAppsException.class,
                        () -> nobody.fetchResource(TENANT, REG, URI, serverApps(nobody)));
        assertEquals(McpAppsException.Code.REFUSED, e.code());
        verifyNoInteractions(client);

        // The connection lookup is tenant-scoped: asking as another tenant for the same
        // registration id gets nothing.
        McpAppsException other =
                assertThrows(
                        McpAppsException.class,
                        () -> svc.fetchResource("t_other", REG, URI, serverApps(svc)));
        assertEquals(McpAppsException.Code.REFUSED, other.code());
        verifyNoInteractions(client);
    }

    @Test
    void aSlowServerTimesOut() {
        McpAppsService quick =
                service(
                        new McpAppsConfig(
                                0,
                                Duration.ofMillis(50),
                                null,
                                List.of(),
                                true,
                                0,
                                null,
                                0,
                                0,
                                0,
                                0),
                        client);
        register(quick);
        when(client.readResource(any(McpSchema.ReadResourceRequest.class)))
                .thenAnswer(
                        inv -> {
                            Thread.sleep(2_000);
                            return html(URI, "text/html;profile=mcp-app", "<p>late</p>");
                        });
        assertEquals(
                McpAppsException.Code.UNAVAILABLE,
                assertThrows(
                                McpAppsException.class,
                                () -> quick.fetchResource(TENANT, REG, URI, serverApps(quick)))
                        .code());
    }

    @Test
    void theFetchedHtmlIsCachedBriefly() {
        when(client.readResource(any(McpSchema.ReadResourceRequest.class)))
                .thenReturn(html(URI, "text/html;profile=mcp-app", "<p>hi</p>"));
        svc.fetchResource(TENANT, REG, URI, serverApps(svc));
        svc.fetchResource(TENANT, REG, URI, serverApps(svc));
        verify(client, times(1)).readResource(any(McpSchema.ReadResourceRequest.class));

        // forgetting the server (disconnect) drops the cached document too
        svc.forgetServer(TENANT, REG);
        register(svc);
        svc.fetchResource(TENANT, REG, URI, serverApps(svc));
        verify(client, times(2)).readResource(any(McpSchema.ReadResourceRequest.class));
    }

    // ---- session / view
    // --------------------------------------------------------------------------------

    @Test
    void viewReturnsHtmlUnderTheClosedCspAndRedactedInput() {
        when(client.readResource(any(McpSchema.ReadResourceRequest.class)))
                .thenReturn(html(URI, "text/html;profile=mcp-app", "<p>hi</p>"));
        Map<String, Object> pointer = mint(svc);
        assertEquals("weather", pointer.get("server"));
        assertEquals("forecast", pointer.get("tool"));
        assertEquals(URI, pointer.get("uri"));

        McpAppsService.AppView v = svc.view((String) pointer.get("appId"));
        assertEquals("<p>hi</p>", v.html());
        assertEquals("text/html;profile=mcp-app", v.mimeType());
        assertTrue(v.csp().contains("default-src 'none'"));
        assertTrue(v.csp().contains("connect-src 'none'"));
        assertTrue(v.csp().contains("frame-src 'none'"));
        assertTrue(v.csp().contains("base-uri 'none'"));
        assertTrue(v.csp().contains("form-action 'none'"));
        assertEquals("{\"temp\":31}", v.toolResult());
        assertFalse(v.toolInput().contains("sk-abcdefghijklmnopqrstuvwxyz"), v.toolInput());
        assertTrue(v.toolInput().contains("Pune"));
    }

    @Test
    void aToolWithoutAnAppGetsNoPointer() {
        McpAppStepHook hook = svc.hookFor(TenantContext.of(TENANT, USER, Set.of()), AGENT, "s");
        assertNull(hook.onToolResult("mcp__weather__refresh", "{}", "ok"));
        assertNull(hook.onToolResult("some_builtin_tool", "{}", "ok"));
        assertEquals(0, svc.sessionCount());
        assertNull(svc.hookFor(null, AGENT, "s"));
    }

    @Test
    void aSessionIsBoundToItsTenantAndUser() {
        when(client.readResource(any(McpSchema.ReadResourceRequest.class)))
                .thenReturn(html(URI, "text/html;profile=mcp-app", "<p>hi</p>"));
        String appId = (String) mint(svc).get("appId");

        as(TENANT, "mallory");
        assertEquals(
                McpAppsException.Code.NOT_FOUND,
                assertThrows(McpAppsException.class, () -> svc.view(appId)).code());
        assertEquals(
                McpAppsException.Code.NOT_FOUND,
                assertThrows(
                                McpAppsException.class,
                                () -> svc.authorizeToolCall(appId, "refresh", "{}"))
                        .code());

        as("t_other", USER);
        assertEquals(
                McpAppsException.Code.NOT_FOUND,
                assertThrows(McpAppsException.class, () -> svc.view(appId)).code());

        TenantContext.clear();
        assertEquals(
                McpAppsException.Code.NOT_FOUND,
                assertThrows(McpAppsException.class, () -> svc.view(appId)).code());
        assertEquals(
                McpAppsException.Code.NOT_FOUND,
                assertThrows(McpAppsException.class, () -> svc.view("nope")).code());
    }

    @Test
    void viewFailsOnceTheServerIsDisconnected() {
        String appId = (String) mint(svc).get("appId");
        svc.forgetServer(TENANT, REG);
        assertEquals(
                McpAppsException.Code.NOT_FOUND,
                assertThrows(McpAppsException.class, () -> svc.view(appId)).code());
    }

    // ---- tools/call vetting
    // ---------------------------------------------------------------------------------

    @Test
    void anAppMayCallAnotherToolOfItsOwnServer() {
        String appId = (String) mint(svc).get("appId");
        McpAppsService.ToolCallAuth auth =
                svc.authorizeToolCall(appId, "refresh", "{\"city\":\"Pune\"}");
        assertEquals("mcp__weather__refresh", auth.finalToolName());
        assertEquals(AGENT, auth.agentId());
        assertEquals("mcpapp:" + appId, auth.logSessionId());
        assertEquals(USER, auth.subject());
    }

    @Test
    void aCallToAnotherServersToolOrAPlatformBuiltinIsRefused() {
        String appId = (String) mint(svc).get("appId");
        for (String name :
                List.of(
                        "send",
                        "mcp__mail__send",
                        "mcp__weather__refresh",
                        "search_knowledge_base",
                        "../refresh")) {
            assertEquals(
                    McpAppsException.Code.REFUSED,
                    assertThrows(
                                    McpAppsException.class,
                                    () -> svc.authorizeToolCall(appId, name, "{}"),
                                    name)
                            .code(),
                    name);
        }
    }

    @Test
    void aNameSharedWithAnotherServerIsAmbiguousAndRefused() {
        String appId = (String) mint(svc).get("appId");
        svc.recordServer(
                TENANT,
                OTHER_REG,
                "mail",
                List.of(new McpAppsService.ToolEntry("refresh", "mcp__weather__refresh", null)));
        assertEquals(
                McpAppsException.Code.REFUSED,
                assertThrows(
                                McpAppsException.class,
                                () -> svc.authorizeToolCall(appId, "refresh", "{}"))
                        .code());
    }

    @Test
    void malformedOrRecursiveArgumentsAreRefused() {
        String appId = (String) mint(svc).get("appId");
        assertEquals(
                McpAppsException.Code.BAD_REQUEST,
                assertThrows(
                                McpAppsException.class,
                                () -> svc.authorizeToolCall(appId, "refresh", "[1]"))
                        .code());
        assertEquals(
                McpAppsException.Code.BAD_REQUEST,
                assertThrows(
                                McpAppsException.class,
                                () -> svc.authorizeToolCall(appId, "refresh", "{oops"))
                        .code());
        assertEquals(
                McpAppsException.Code.BAD_REQUEST,
                assertThrows(McpAppsException.class, () -> svc.authorizeToolCall(appId, " ", "{}"))
                        .code());
        assertEquals(
                McpAppsException.Code.REFUSED,
                assertThrows(
                                McpAppsException.class,
                                () ->
                                        svc.authorizeToolCall(
                                                appId,
                                                "refresh",
                                                "{\"next\":\"ui://weather/forecast\"}"))
                        .code());
        assertEquals(
                McpAppsException.Code.REFUSED,
                assertThrows(
                                McpAppsException.class,
                                () ->
                                        svc.authorizeToolCall(
                                                appId, "refresh", "{\"a\":[{\"b\":\"UI://x/y\"}]}"))
                        .code());
        assertEquals(
                McpAppsException.Code.TOO_LARGE,
                assertThrows(
                                McpAppsException.class,
                                () ->
                                        svc.authorizeToolCall(
                                                appId,
                                                "refresh",
                                                "{\"a\":\"" + "x".repeat(40_000) + "\"}"))
                        .code());
    }

    @Test
    void toolCallsAreRateLimitedPerUser() {
        McpAppsService limited =
                service(
                        new McpAppsConfig(0, null, null, List.of(), true, 0, null, 0, 3, 0, 0),
                        client);
        register(limited);
        String appId =
                (String)
                        limited.hookFor(TenantContext.of(TENANT, USER, Set.of()), AGENT, "s")
                                .onToolResult("mcp__weather__forecast", "{}", "ok")
                                .get("appId");
        for (int i = 0; i < 3; i++) {
            limited.authorizeToolCall(appId, "refresh", "{}");
        }
        assertEquals(
                McpAppsException.Code.RATE_LIMITED,
                assertThrows(
                                McpAppsException.class,
                                () -> limited.authorizeToolCall(appId, "refresh", "{}"))
                        .code());
    }

    @Test
    void sessionsAreBoundedAndOldestEvicted() {
        McpAppsService tiny =
                service(
                        new McpAppsConfig(0, null, null, List.of(), true, 0, null, 3, 0, 0, 0),
                        client);
        register(tiny);
        McpAppStepHook hook = tiny.hookFor(TenantContext.of(TENANT, USER, Set.of()), AGENT, "s");
        for (int i = 0; i < 10; i++) {
            hook.onToolResult("mcp__weather__forecast", "{}", "ok");
        }
        assertTrue(tiny.sessionCount() <= 3, "kept " + tiny.sessionCount());
    }

    private static McpAppsService.ServerApps serverApps(McpAppsService s) {
        return new McpAppsService.ServerApps(
                "weather",
                Map.of("forecast", "mcp__weather__forecast"),
                Map.of("mcp__weather__forecast", decl(URI)));
    }
}
