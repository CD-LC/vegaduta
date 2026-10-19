package com.agenticai.core.common.plugins;

import com.agenticai.core.common.plugins.PluginCatalogController.PluginCatalog;
import com.agenticai.core.tenancy.PlatformSettingsService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * The effective plugin catalog: the platform setting {@link PluginCatalogController#CATALOG_KEY}
 * when it holds valid JSON, otherwise {@link PluginCatalogController#DEFAULT_CATALOG}. Used by the
 * public catalog endpoint and by {@link PluginArtifactService} (uploads are validated against the
 * effective catalog). A service rather than a controller method so non-web code never injects a
 * controller; the jobs and worker shapes do not load controllers.
 */
@Service
public class PluginCatalogService {

    private static final Logger log = LoggerFactory.getLogger(PluginCatalogService.class);

    private final PlatformSettingsService platformSettingsService;
    private final ObjectMapper objectMapper;

    public PluginCatalogService(PlatformSettingsService platformSettingsService, ObjectMapper objectMapper) {
        this.platformSettingsService = platformSettingsService;
        this.objectMapper = objectMapper;
    }

    /** Stored-setting-wins / corrupt-falls-back resolution. */
    public PluginCatalog resolveCatalog() {
        String stored = platformSettingsService.getString(PluginCatalogController.CATALOG_KEY, null);
        if (stored == null || stored.isBlank()) {
            return PluginCatalogController.DEFAULT_CATALOG;
        }
        try {
            return objectMapper.readValue(stored, PluginCatalog.class);
        } catch (com.fasterxml.jackson.core.JacksonException e) {
            log.warn("Platform setting '{}' holds unparseable catalog JSON - serving the built-in default. {}",
                PluginCatalogController.CATALOG_KEY, e.getMessage());
            return PluginCatalogController.DEFAULT_CATALOG;
        }
    }
}
