package com.agenticai.core.knowledge.connector;

/**
 * The five live-sync source types (P2 product-completeness: "no
 * Notion/Drive/Confluence/SharePoint/web-crawl live sync"). Each maps to one
 * {@link ConnectorFetcher} bean that knows the provider's API and produces
 * {@link ConnectorDocument}s the shared sync path ingests identically to
 * uploads.
 */
public enum ConnectorType {
    NOTION,
    GOOGLE_DRIVE,
    CONFLUENCE,
    SHAREPOINT,
    WEB_CRAWL;

    public static ConnectorType parse(String raw) {
        if (raw == null) {
            return null;
        }
        try {
            return valueOf(raw.trim().toUpperCase(java.util.Locale.ROOT).replace('-', '_'));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
