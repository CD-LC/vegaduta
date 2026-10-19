package com.agenticai.core.documentgen.brand;

import com.agenticai.core.security.TenantLimits;
import com.agenticai.core.storage.FileStorageService;
import com.agenticai.core.tenancy.TenantBrandingLogoController;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * Picks the brand for a generated document: the tenant's own (logo and/or
 * display name, only when set AND the plan carries the white-label
 * entitlement - {@link TenantLimits#brandDisplayName} / {@link
 * TenantLimits#brandLogoUrl} already return empty otherwise) or the VegaDuta
 * wordmark. Never throws: a document must still render if branding lookup or
 * the logo read fails, so every failure degrades to the platform brand.
 *
 * <p>A tenant logo is read from our own storage (the URL the branding upload
 * minted, mapped back to its key); an externally hosted URL is NOT fetched -
 * that would make document generation an SSRF primitive - so such a tenant
 * gets their name without a picture.
 */
@Component
public class DocumentBrandResolver {

    private static final Logger log = LoggerFactory.getLogger(DocumentBrandResolver.class);
    private static final int MAX_LOGO_BYTES = 2 * 1024 * 1024;
    private static final DocumentBrand PLATFORM = new DocumentBrand("", loadPlatformLogo(), true, true);

    private final ObjectProvider<TenantLimits> tenantLimits;
    private final ObjectProvider<FileStorageService> storage;

    public DocumentBrandResolver(ObjectProvider<TenantLimits> tenantLimits, ObjectProvider<FileStorageService> storage) {
        this.tenantLimits = tenantLimits;
        this.storage = storage;
    }

    /** The VegaDuta brand, for callers with no tenant. */
    public static DocumentBrand platform() {
        return PLATFORM;
    }

    public DocumentBrand resolve(String tenantId) {
        if (tenantId == null || tenantId.isBlank()) {
            return PLATFORM;
        }
        try {
            TenantLimits limits = tenantLimits.getIfAvailable();
            if (limits == null) {
                return PLATFORM;
            }
            Optional<String> name = limits.brandDisplayName(tenantId).map(String::trim).filter(n -> !n.isEmpty());
            Optional<String> logoUrl = limits.brandLogoUrl(tenantId);
            if (name.isEmpty() && logoUrl.isEmpty()) {
                return PLATFORM;
            }
            byte[] logo = null;
            boolean png = true;
            String key = logoUrl.map(url -> TenantBrandingLogoController.objectKeyFor(url, tenantId)).orElse(null);
            if (key != null && (key.endsWith(".png") || key.endsWith(".jpg") || key.endsWith(".jpeg"))) {
                logo = readLogo(key);
                png = key.endsWith(".png");
            }
            if (logo == null && name.isEmpty()) {
                // Only an unembeddable logo (SVG/WebP/external): show the platform brand rather than nothing.
                return PLATFORM;
            }
            return new DocumentBrand(name.orElse(""), logo, png, false);
        } catch (RuntimeException e) {
            log.warn("Document branding lookup failed for tenant {} - using the platform brand: {}", tenantId, e.toString());
            return PLATFORM;
        }
    }

    private byte[] readLogo(String key) {
        FileStorageService files = storage.getIfAvailable();
        if (files == null) {
            return null;
        }
        try (InputStream in = files.get(key)) {
            byte[] bytes = in.readNBytes(MAX_LOGO_BYTES + 1);
            return bytes.length > MAX_LOGO_BYTES || bytes.length == 0 ? null : bytes;
        } catch (IOException | RuntimeException e) {
            log.warn("Tenant brand logo {} unreadable - document gets the name only: {}", key, e.toString());
            return null;
        }
    }

    private static byte[] loadPlatformLogo() {
        try (InputStream in = DocumentBrandResolver.class.getResourceAsStream("/brand/vegaduta-wordmark.png")) {
            return in == null ? null : in.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
