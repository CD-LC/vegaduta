package com.agenticai.core.graphmemory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.agenticai.core.security.TenantContext;
import com.agenticai.core.tenancy.MissingTenantContextException;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Covers {@link GraphMemorySchemaSupport#currentGraphName()}: the {@code <tenant_schema>_kg}
 * derivation, the no-context public-schema fallback and the fail-closed refusal to derive a graph
 * name for a protected/system schema.
 */
class GraphMemorySchemaSupportTest {

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    @Test
    void noTenantContextYieldsThePublicGraphName() {
        TenantContext.clear();

        assertThat(GraphMemorySchemaSupport.currentGraphName()).isEqualTo("public_kg");
    }

    @Test
    void aTenantContextDerivesTheSanitizedSchemaGraphName() {
        TenantContext.set(TenantContext.of("acme-corp", "user-1", Set.of()));

        // tenant_id "acme-corp" sanitizes to schema "acme_corp" -> graph "acme_corp_kg".
        assertThat(GraphMemorySchemaSupport.currentGraphName()).isEqualTo("acme_corp_kg");
    }

    @Test
    void aProtectedSchemaNeverBecomesAGraphName() {
        // "control_plane" sanitizes straight to the control-plane schema - graph memory is
        // tenant data, so this must refuse rather than read a shared graph.
        TenantContext.set(TenantContext.of("control_plane", "user-1", Set.of()));

        assertThatThrownBy(GraphMemorySchemaSupport::currentGraphName)
                .isInstanceOf(MissingTenantContextException.class);
    }
}
