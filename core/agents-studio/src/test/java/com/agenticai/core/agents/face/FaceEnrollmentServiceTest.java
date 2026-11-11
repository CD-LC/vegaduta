package com.agenticai.core.agents.face;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.agents.core.AgentService;
import com.agenticai.core.agents.face.FaceEnrollmentService.FaceEnrollmentRecord;
import com.agenticai.core.knowledge.KnowledgeCollectionService;
import com.agenticai.core.security.TenantContext;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

/**
 * Consent gates, input checks and the vector-literal round trip of {@link FaceEnrollmentService}.
 */
class FaceEnrollmentServiceTest {

    private static final UUID AGENT = UUID.randomUUID();
    private static final UUID COLLECTION = UUID.randomUUID();

    private JdbcTemplate jdbc;
    private AgentService agents;
    private KnowledgeCollectionService collections;
    private FaceEnrollmentService service;

    @BeforeEach
    void setUp() {
        jdbc = mock(JdbcTemplate.class);
        agents = mock(AgentService.class);
        collections = mock(KnowledgeCollectionService.class);
        service = new FaceEnrollmentService(jdbc, agents, collections);
        when(agents.isBiometricConsentEnabled(AGENT)).thenReturn(true);
        TenantContext.set(TenantContext.of("acme", "u1", Set.of()));
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private static List<Double> embedding(int size) {
        List<Double> values = new ArrayList<>();
        for (int i = 0; i < size; i++) {
            values.add(i / 1000.0);
        }
        return values;
    }

    @Test
    void anAgentWithoutBiometricConsentCannotEnrollOrList() {
        when(agents.isBiometricConsentEnabled(AGENT)).thenReturn(false);

        assertThatThrownBy(
                        () -> service.enroll(AGENT, COLLECTION, null, "Asha", embedding(512), true))
                .isInstanceOf(BiometricConsentNotEnabledException.class)
                .hasMessageContaining("biometric-consent");
        assertThatThrownBy(() -> service.listForCollection(AGENT, COLLECTION))
                .isInstanceOf(BiometricConsentNotEnabledException.class);
        verify(jdbc, never()).update(anyString(), any(Object[].class));
    }

    @Test
    void enrollmentNeedsExplicitConsentAndAWellFormedEmbeddingAndName() {
        assertThatThrownBy(
                        () ->
                                service.enroll(
                                        AGENT, COLLECTION, null, "Asha", embedding(512), false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("explicit consent");
        assertThatThrownBy(
                        () -> service.enroll(AGENT, COLLECTION, null, "Asha", embedding(511), true))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("got: 511");
        assertThatThrownBy(() -> service.enroll(AGENT, COLLECTION, null, "Asha", null, true))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("got: null");
        assertThatThrownBy(
                        () -> service.enroll(AGENT, COLLECTION, null, "  ", embedding(512), true))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("personName");
        assertThatThrownBy(
                        () -> service.enroll(AGENT, COLLECTION, null, null, embedding(512), true))
                .isInstanceOf(IllegalArgumentException.class);
        verify(collections, org.mockito.Mockito.atLeastOnce()).requireExists(COLLECTION);
        verify(jdbc, never()).update(anyString(), any(Object[].class));
    }

    @Test
    void anEnrollmentIsInsertedWithATrimmedNameAndAVectorLiteral() {
        UUID doc = UUID.randomUUID();

        UUID id = service.enroll(AGENT, COLLECTION, doc, "  Asha  ", embedding(512), true);

        ArgumentCaptor<Object[]> args = ArgumentCaptor.forClass(Object[].class);
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc).update(sql.capture(), args.capture());
        assertThat(sql.getValue())
                .contains("knowledge_face_enrollment")
                .contains("?::public.vector");
        assertThat(args.getValue()[0]).isEqualTo(id);
        assertThat(args.getValue()[1]).isEqualTo(COLLECTION);
        assertThat(args.getValue()[2]).isEqualTo(doc);
        assertThat(args.getValue()[3]).isEqualTo("Asha");
        String literal = (String) args.getValue()[4];
        assertThat(literal).startsWith("[0.0,0.001,").endsWith("]");
        assertThat(literal.split(",")).hasSize(512);
    }

    @Test
    @SuppressWarnings("unchecked")
    void listingMapsRowsAndParsesTheVectorText() throws Exception {
        UUID rowId = UUID.randomUUID();
        Instant created = Instant.parse("2026-10-01T10:00:00Z");
        ResultSet rs = mock(ResultSet.class);
        when(rs.getString("id")).thenReturn(rowId.toString());
        when(rs.getString("person_name")).thenReturn("Asha");
        when(rs.getString("embedding_text")).thenReturn("[0.5, -0.25,1]");
        when(rs.getTimestamp("created_at")).thenReturn(Timestamp.from(created));
        ArgumentCaptor<RowMapper<FaceEnrollmentRecord>> mapper =
                ArgumentCaptor.forClass(RowMapper.class);
        when(jdbc.query(anyString(), mapper.capture(), eq(COLLECTION))).thenReturn(List.of());

        assertThat(service.listForCollection(AGENT, COLLECTION)).isEmpty();

        FaceEnrollmentRecord mapped = mapper.getValue().mapRow(rs, 0);
        assertThat(mapped.id()).isEqualTo(rowId);
        assertThat(mapped.personName()).isEqualTo("Asha");
        assertThat(mapped.embedding()).containsExactly(0.5, -0.25, 1.0);
        assertThat(mapped.createdAt()).isEqualTo(created);

        when(rs.getString("embedding_text")).thenReturn("[]");
        assertThat(mapper.getValue().mapRow(rs, 1).embedding()).isEmpty();
    }

    @Test
    void erasureNeverNeedsConsent() {
        when(agents.isBiometricConsentEnabled(any())).thenReturn(false);
        UUID id = UUID.randomUUID();

        service.delete(id);

        verify(jdbc).update(org.mockito.ArgumentMatchers.contains("DELETE FROM"), eq((Object) id));
        verify(agents, never()).isBiometricConsentEnabled(any());
    }
}
