package com.agenticai.core.documentgen.brand;

/**
 * The brand a generated document carries in its header (2026-10-09, direct
 * instruction: reports and documents show the tenant's own logo and name when
 * the tenant has set branding, and the VegaDuta logo and name otherwise).
 *
 * @param name      text to print beside the logo; empty when the logo already
 *                  spells the name (the VegaDuta wordmark does)
 * @param logo      PNG or JPEG bytes, or null when there is no embeddable logo
 *                  (none set, or an SVG/WebP the document libraries cannot draw)
 * @param logoIsPng true for PNG, false for JPEG; ignored when {@code logo} is null
 * @param platformDefault true when this is the VegaDuta fallback, not a tenant brand
 */
public record DocumentBrand(String name, byte[] logo, boolean logoIsPng, boolean platformDefault) {

    public static final String PLATFORM_NAME = "VegaDūta";

    public boolean hasLogo() {
        return logo != null && logo.length > 0;
    }

    public boolean hasName() {
        return name != null && !name.isBlank();
    }
}
