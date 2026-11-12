package com.agenticai.core.knowledge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.knowledge.rag.ParentChunkStore;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;

/**
 * G7 (V711): a parent chunk carries the trust of the children written with it, the parent read path
 * only returns TRUSTED parents, and quarantine approve/reject keep the parents in step with their
 * children.
 */
class ParentChunkTrustTest {

    private final UUID collection = UUID.randomUUID();

    @Test
    @SuppressWarnings("unchecked")
    void insertWritesTheChildrensTrustAndTaintOnEveryParent() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        ParentChunkStore store = new ParentChunkStore(jdbc);
        List<ParentChunkStore.ParentRow> rows =
                List.of(
                        new ParentChunkStore.ParentRow(UUID.randomUUID(), 0, "a"),
                        new ParentChunkStore.ParentRow(UUID.randomUUID(), 1, "b"));

        store.insertAll(
                collection, null, rows, ChunkProvenance.quarantined(List.of("fetch_web_page")));

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<List<Object[]>> args = ArgumentCaptor.forClass(List.class);
        verify(jdbc).batchUpdate(sql.capture(), args.capture());
        assertTrue(sql.getValue().contains("trust, source_taint"), sql.getValue());
        for (Object[] row : args.getValue()) {
            assertEquals("QUARANTINED", row[5]);
            assertEquals("[\"fetch_web_page\"]", row[6]);
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void insertWithoutProvenanceFailsClosed() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        new ParentChunkStore(jdbc)
                .insertAll(
                        collection,
                        null,
                        List.of(new ParentChunkStore.ParentRow(UUID.randomUUID(), 0, "a")),
                        null);

        ArgumentCaptor<List<Object[]>> args = ArgumentCaptor.forClass(List.class);
        verify(jdbc).batchUpdate(anyString(), args.capture());
        assertEquals("QUARANTINED", args.getValue().get(0)[5]);
    }

    @Test
    void trustedIngestionWritesTrustedParents() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        new ParentChunkStore(jdbc)
                .insertAll(
                        collection,
                        null,
                        List.of(new ParentChunkStore.ParentRow(UUID.randomUUID(), 0, "a")),
                        ChunkProvenance.TRUSTED);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Object[]>> args = ArgumentCaptor.forClass(List.class);
        verify(jdbc).batchUpdate(anyString(), args.capture());
        assertEquals("TRUSTED", args.getValue().get(0)[5]);
    }

    @Test
    void parentLookupOnlyJoinsTrustedParents() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        new ParentChunkStore(jdbc).linksFor(List.of(UUID.randomUUID()));

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc).query(sql.capture(), any(RowCallbackHandler.class), any(Object[].class));
        assertTrue(sql.getValue().contains("p.trust = 'TRUSTED'"), sql.getValue());
    }

    @Test
    void approvingAChunkSettlesItsParentsAndOnlyReleasesThemWhenNoSiblingIsHeld() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        UUID chunk = UUID.randomUUID();
        UUID parent = UUID.randomUUID();
        stubAffected(jdbc, parent);
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);

        assertTrue(new KnowledgeQuarantineService(jdbc).approve(chunk));

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc, atLeastOnce()).update(sql.capture(), any(Object[].class));
        String release =
                sql.getAllValues().stream()
                        .filter(s -> s.contains("knowledge_parent_chunk") && s.startsWith("UPDATE"))
                        .findFirst()
                        .orElseThrow(
                                () ->
                                        new AssertionError(
                                                "no parent release statement: "
                                                        + sql.getAllValues()));
        assertTrue(release.contains("SET trust = 'TRUSTED'"), release);
        assertTrue(
                release.contains("NOT EXISTS") && release.contains("c.trust = 'QUARANTINED'"),
                release);
    }

    @Test
    void rejectingChunksDropsParentsNoChildPointsToAnymore() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        stubAffected(jdbc, UUID.randomUUID());
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);

        assertEquals(1, new KnowledgeQuarantineService(jdbc).rejectNote(collection, "note"));

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc, atLeastOnce()).update(sql.capture(), any(Object[].class));
        assertTrue(
                sql.getAllValues().stream()
                        .anyMatch(
                                s ->
                                        s.startsWith("DELETE FROM")
                                                && s.contains("knowledge_parent_chunk")),
                sql.getAllValues().toString());
    }

    @Test
    void nothingIsSettledWhenNoChunkChanged() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        stubAffected(jdbc, UUID.randomUUID());
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(0);

        assertEquals(0, new KnowledgeQuarantineService(jdbc).approveNote(collection, "note"));

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc, atLeastOnce()).update(sql.capture(), any(Object[].class));
        assertTrue(
                sql.getAllValues().stream().noneMatch(s -> s.contains("knowledge_parent_chunk")));
        verify(jdbc, never()).batchUpdate(anyString(), any(List.class));
    }

    /**
     * Makes the "which parents do these held chunks point to" query report one chunk with one
     * parent.
     */
    private void stubAffected(JdbcTemplate jdbc, UUID parent) {
        org.mockito.Mockito.doAnswer(
                        inv -> {
                            RowCallbackHandler handler = inv.getArgument(1);
                            java.sql.ResultSet rs = mock(java.sql.ResultSet.class);
                            when(rs.getString("collection_id")).thenReturn(collection.toString());
                            when(rs.getString("parent_id")).thenReturn(parent.toString());
                            handler.processRow(rs);
                            return null;
                        })
                .when(jdbc)
                .query(anyString(), any(RowCallbackHandler.class), any(Object[].class));
    }
}
