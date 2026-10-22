package com.agenticai.core.common.plugins;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.common.plugins.PluginArtifactService.ArtifactDownload;
import com.agenticai.core.common.plugins.PluginArtifactService.StoredArtifact;
import com.agenticai.core.security.TenantContext;
import com.agenticai.core.storage.FileStorageService;
import com.agenticai.core.tenancy.PlatformSettingsService;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

/**
 * IDE-plugin plan B3 - {@link PluginArtifactService}: strict three-segment key validation (never a
 * bucket browser), server-computed sha256/size, the bare-key TenantContext discipline around every
 * storage call, and 404 semantics for a missing/malformed download path. Plain unit tests, no
 * MockMvc - same convention as {@code PluginCatalogControllerTest}.
 */
class PluginArtifactServiceTest {

    private static final byte[] CONTENT = "hello world".getBytes(StandardCharsets.UTF_8);

    /** SHA-256 of "hello world", the fixed vector every sha256 tool agrees on. */
    private static final String CONTENT_SHA256 =
            "b94d27b9934d3e08a52e52d7da7dabfac484efe37a5380ee9088f7ace2efcde9";

    private FileStorageService storage;
    private PluginArtifactService service;

    @BeforeEach
    void setUp() {
        storage = mock(FileStorageService.class);
        PlatformSettingsService settings = mock(PlatformSettingsService.class);
        when(settings.getString(PluginCatalogController.CATALOG_KEY, null)).thenReturn(null);
        service =
                new PluginArtifactService(
                        storage,
                        new PluginCatalogService(
                                settings, com.agenticai.core.json.JsonMappers.plain()));
        TenantContext.clear();
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private static MockMultipartFile vsix(String fileName) {
        return new MockMultipartFile("file", fileName, "application/octet-stream", CONTENT);
    }

    // ------------------------------------------------------------------
    // Upload: validation
    // ------------------------------------------------------------------

    @Test
    void uploadRejectsPluginIdNotInCatalog() {
        assertThrows(
                IllegalArgumentException.class,
                () -> service.store("not-a-plugin", "1.0.0", vsix("x.vsix")));
        verify(storage, never()).put(anyString(), any(), anyString());
    }

    @Test
    void uploadRejectsTraversalAndSeparatorSegments() {
        // ".." matches the character class ('.' is legal in filenames), so it must
        // be rejected explicitly; '/' and '%' (as in an encoded %2F) fail the class.
        assertThrows(
                IllegalArgumentException.class,
                () -> service.store("vscode", "..", vsix("x.vsix")));
        assertThrows(
                IllegalArgumentException.class,
                () -> service.store("vscode", "1.0/evil", vsix("x.vsix")));
        assertThrows(
                IllegalArgumentException.class,
                () -> service.store("vscode", "1.0%2Fevil", vsix("x.vsix")));
        assertThrows(
                IllegalArgumentException.class,
                () -> service.store("vscode", "1.0.0", vsix("a b.vsix")));
        verify(storage, never()).put(anyString(), any(), anyString());
    }

    @Test
    void uploadRejectsDisallowedExtensions() {
        for (String bad : Set.of("plugin.exe", "plugin.vsix.html", "plugin", "plugin.jar")) {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> service.store("vscode", "1.0.0", vsix(bad)),
                    bad);
        }
        verify(storage, never()).put(anyString(), any(), anyString());
    }

    @Test
    void uploadRejectsEmptyFile() {
        MockMultipartFile empty =
                new MockMultipartFile("file", "x.vsix", "application/octet-stream", new byte[0]);
        assertThrows(IllegalArgumentException.class, () -> service.store("vscode", "1.0.0", empty));
        verify(storage, never()).put(anyString(), any(), anyString());
    }

    // ------------------------------------------------------------------
    // Upload: happy path
    // ------------------------------------------------------------------

    @Test
    void uploadComputesSha256AndSizeAndStoresUnderTheExpectedKey() {
        StoredArtifact stored = service.store("vscode", "1.2.3", vsix("vegaduta-1.2.3.vsix"));

        verify(storage)
                .put(
                        eq("plugin-artifacts/vscode/1.2.3/vegaduta-1.2.3.vsix"),
                        eq(CONTENT),
                        eq("application/octet-stream"));
        assertEquals("vscode", stored.pluginId());
        assertEquals("1.2.3", stored.version());
        assertEquals("vegaduta-1.2.3.vsix", stored.artifact().fileName());
        assertEquals(CONTENT.length, stored.artifact().sizeBytes());
        assertEquals(CONTENT_SHA256, stored.artifact().sha256());
    }

    @Test
    void uploadStripsClientSuppliedDirectoriesFromTheFileName() {
        StoredArtifact stored = service.store("chrome", "2.0.0", vsix("C:\\builds\\vegaduta.crx"));

        verify(storage)
                .put(eq("plugin-artifacts/chrome/2.0.0/vegaduta.crx"), eq(CONTENT), anyString());
        assertEquals("vegaduta.crx", stored.artifact().fileName());
    }

    @Test
    void uploadClearsTenantContextAroundThePutAndRestoresItAfter() {
        // A platform-admin upload runs inside a JWT request whose TenantContext
        // would make S3FileStorageService write tenant/<schema>/plugin-artifacts/...
        // while the permitAll download later reads the bare key - so the put MUST
        // see no context, and the surrounding request MUST get its context back.
        TenantContext adminContext =
                TenantContext.of("admin_tenant", "admin@vegaduta", Set.of("platform-admin"));
        TenantContext.set(adminContext);
        AtomicBoolean contextPresentDuringPut = new AtomicBoolean(true);
        org.mockito.Mockito.doAnswer(
                        invocation -> {
                            contextPresentDuringPut.set(TenantContext.current().isPresent());
                            return null;
                        })
                .when(storage)
                .put(anyString(), any(), anyString());

        service.store("jetbrains", "1.0.0", vsix("vegaduta.zip"));

        assertFalse(contextPresentDuringPut.get(), "put must run with NO TenantContext (bare key)");
        assertTrue(TenantContext.current().isPresent(), "caller's TenantContext must be restored");
        assertEquals("admin_tenant", TenantContext.current().orElseThrow().getTenantId());
    }

    // ------------------------------------------------------------------
    // Download
    // ------------------------------------------------------------------

    @Test
    void downloadStreamsExistingArtifactWithSize() {
        String key = "plugin-artifacts/vscode/1.2.3/vegaduta-1.2.3.vsix";
        when(storage.exists(key)).thenReturn(true);
        when(storage.size(key)).thenReturn((long) CONTENT.length);
        when(storage.get(key)).thenReturn(new ByteArrayInputStream(CONTENT));

        ArtifactDownload download = service.open("vscode", "1.2.3", "vegaduta-1.2.3.vsix");

        assertEquals(CONTENT.length, download.sizeBytes());
        assertEquals("vegaduta-1.2.3.vsix", download.fileName());
        assertNotNull(download.stream());
    }

    @Test
    void downloadOfMissingKeyIs404() {
        when(storage.exists("plugin-artifacts/vscode/9.9.9/nope.vsix")).thenReturn(false);

        assertThrows(
                NoSuchElementException.class, // -> 404 via GlobalExceptionHandler
                () -> service.open("vscode", "9.9.9", "nope.vsix"));
        verify(storage, never()).get(anyString());
    }

    @Test
    void downloadRejectsTraversalSeparatorAndExtensionProbesAs404WithoutTouchingStorage() {
        // Same 404 as a genuinely absent object (anti-enumeration), and storage is
        // never consulted - this endpoint must never behave like a bucket browser.
        assertThrows(NoSuchElementException.class, () -> service.open("..", "1.0.0", "x.vsix"));
        assertThrows(NoSuchElementException.class, () -> service.open("vscode", "..", "x.vsix"));
        assertThrows(NoSuchElementException.class, () -> service.open("vscode", "1.0.0", ".."));
        assertThrows(
                NoSuchElementException.class, () -> service.open("vscode", "1.0.0", "a/b.vsix"));
        assertThrows(
                NoSuchElementException.class, () -> service.open("vscode", "1.0.0", "a%2Fb.vsix"));
        assertThrows(
                NoSuchElementException.class, () -> service.open("vscode", "1.0.0", "secrets.txt"));
        assertThrows(
                NoSuchElementException.class,
                () -> service.open("tenant", "acme", "export.zip.vsix.bak"));
        verify(storage, never()).exists(anyString());
        verify(storage, never()).get(anyString());
    }

    @Test
    void downloadClearsTenantContextAroundStorageReadsAndRestoresItAfter() {
        // An authenticated user clicking Download must still read the BARE key -
        // their tenant prefix would miss - and keep their context afterwards.
        TenantContext userContext = TenantContext.of("tenant_a", "user@a", Set.of("user"));
        TenantContext.set(userContext);
        String key = "plugin-artifacts/vscode/1.0.0/vegaduta.vsix";
        AtomicBoolean contextPresentDuringRead = new AtomicBoolean(true);
        when(storage.exists(key))
                .thenAnswer(
                        invocation -> {
                            contextPresentDuringRead.set(TenantContext.current().isPresent());
                            return true;
                        });
        when(storage.size(key)).thenReturn(11L);
        when(storage.get(key)).thenReturn(new ByteArrayInputStream(CONTENT));

        service.open("vscode", "1.0.0", "vegaduta.vsix");

        assertFalse(
                contextPresentDuringRead.get(),
                "storage reads must run with NO TenantContext (bare key)");
        assertTrue(TenantContext.current().isPresent(), "caller's TenantContext must be restored");
        assertEquals("tenant_a", TenantContext.current().orElseThrow().getTenantId());
    }
}
