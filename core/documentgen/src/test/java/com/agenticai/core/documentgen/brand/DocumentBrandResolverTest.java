package com.agenticai.core.documentgen.brand;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.agenticai.core.security.TenantLimits;
import com.agenticai.core.storage.FileStorageService;
import java.io.ByteArrayInputStream;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

class DocumentBrandResolverTest {

    private final TenantLimits limits = mock(TenantLimits.class);
    private final FileStorageService storage = mock(FileStorageService.class);

    private DocumentBrandResolver resolver() {
        StaticListableBeanFactory beans = new StaticListableBeanFactory();
        beans.addBean("limits", limits);
        beans.addBean("storage", storage);
        return new DocumentBrandResolver(beans.getBeanProvider(TenantLimits.class), beans.getBeanProvider(FileStorageService.class));
    }

    @Test
    void noBrandingSetMeansThePlatformBrand() {
        when(limits.brandDisplayName("t1")).thenReturn(Optional.empty());
        when(limits.brandLogoUrl("t1")).thenReturn(Optional.empty());
        assertSame(DocumentBrandResolver.platform(), resolver().resolve("t1"));
    }

    @Test
    void noTenantMeansThePlatformBrand() {
        assertSame(DocumentBrandResolver.platform(), resolver().resolve(null));
    }

    @Test
    void aBrandNameAloneIsUsed() {
        when(limits.brandDisplayName("t1")).thenReturn(Optional.of("Acme Corp"));
        when(limits.brandLogoUrl("t1")).thenReturn(Optional.empty());
        DocumentBrand brand = resolver().resolve("t1");
        assertEquals("Acme Corp", brand.name());
        assertFalse(brand.platformDefault());
        assertFalse(brand.hasLogo());
    }

    @Test
    void anUploadedPngLogoIsReadFromOurOwnStorage() {
        byte[] png = DocumentBrandResolver.platform().logo();
        when(limits.brandDisplayName("t1")).thenReturn(Optional.of("Acme"));
        when(limits.brandLogoUrl("t1")).thenReturn(
            Optional.of("https://api.vegaduta.ai/api/share/branding/t1/logo.png"));
        when(storage.get(anyString())).thenReturn(new ByteArrayInputStream(png));
        DocumentBrand brand = resolver().resolve("t1");
        assertTrue(brand.hasLogo());
        assertTrue(brand.logoIsPng());
    }

    @Test
    void anExternallyHostedLogoIsNeverFetched() {
        when(limits.brandDisplayName("t1")).thenReturn(Optional.of("Acme"));
        when(limits.brandLogoUrl("t1")).thenReturn(Optional.of("https://evil.example/logo.png"));
        DocumentBrand brand = resolver().resolve("t1");
        assertEquals("Acme", brand.name());
        assertFalse(brand.hasLogo());
    }

    @Test
    void aLookupFailureDegradesToThePlatformBrand() {
        when(limits.brandDisplayName("t1")).thenThrow(new IllegalStateException("db down"));
        assertSame(DocumentBrandResolver.platform(), resolver().resolve("t1"));
    }
}
