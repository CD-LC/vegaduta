package com.agenticai.core.tenancy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.agenticai.core.security.TenantContext;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * {@code GET /api/tenant/feature-flags/{key}/evaluate} - the tenant-facing
 * read added alongside the "edge (on-device) option per AI feature" effort.
 * Plain unit test calling the controller method directly, no MockMvc/web
 * layer - the established convention in this module's tests (see {@code
 * TenantSettingsControllerTest}'s header note). No decision logic of its own
 * to test here beyond "delegates to FeatureFlagService.evaluate with the
 * current TenantContext's tenant id and echoes the key back" -
 * FeatureFlagService's own fail-open/bucketing behavior is that class's
 * responsibility, already covered elsewhere.
 */
class TenantFeatureFlagControllerTest {

    private static final String TENANT_ID = "acme";
    private static final String FLAG_KEY = "edge.ask-ai";

    private FeatureFlagService featureFlagService;
    private TenantFeatureFlagController controller;

    @BeforeEach
    void setUp() {
        featureFlagService = mock(FeatureFlagService.class);
        controller = new TenantFeatureFlagController(featureFlagService);
        TenantContext.set(TenantContext.of(TENANT_ID, "user@acme", Set.of("tenant-member")));
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void evaluateEchoesTheKeyAndReturnsWhateverTheServiceDecides() {
        when(featureFlagService.evaluate(FLAG_KEY, TENANT_ID)).thenReturn(FeatureFlagService.TREATMENT);

        TenantFeatureFlagController.FeatureFlagEvaluationResponse response = controller.evaluate(FLAG_KEY);

        assertEquals(FLAG_KEY, response.key());
        assertEquals(FeatureFlagService.TREATMENT, response.variant());
    }

    @Test
    void evaluateReturnsControlWhenTheServiceFailsOpen() {
        // FeatureFlagService.evaluate itself never throws (see its own
        // javadoc) - a missing/disabled flag resolves to CONTROL, which this
        // controller must pass straight through unchanged.
        when(featureFlagService.evaluate(FLAG_KEY, TENANT_ID)).thenReturn(FeatureFlagService.CONTROL);

        TenantFeatureFlagController.FeatureFlagEvaluationResponse response = controller.evaluate(FLAG_KEY);

        assertEquals(FeatureFlagService.CONTROL, response.variant());
    }

    @Test
    void evaluateWithNoTenantContextThrowsRatherThanLeakingAcrossTenants() {
        TenantContext.clear();

        assertThrows(IllegalStateException.class, () -> controller.evaluate(FLAG_KEY));
    }
}
