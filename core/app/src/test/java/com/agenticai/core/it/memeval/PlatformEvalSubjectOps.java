package com.agenticai.core.it.memeval;

import com.agenticai.core.it.memeval.ProbeAnswerer.RecalledLine;
import com.agenticai.core.quality.memeval.MemoryRun.InjectedItem;
import com.agenticai.core.quality.memeval.MemoryRun.StoreItem;
import com.agenticai.core.quality.memeval.MemoryScenario.Fact;
import com.agenticai.core.quality.memeval.MemoryScenario.Probe;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Oracle-injection, resolution and snapshot helpers of {@link PlatformEvalSubject}, extracted so
 * the subject stays under the file-size cap. Package-private.
 */
final class PlatformEvalSubjectOps {

    private final PlatformEvalSubject owner;

    PlatformEvalSubjectOps(PlatformEvalSubject owner) {
        this.owner = owner;
    }

    void injectOracle(
            PlatformEvalSubject.Case c,
            Probe probe,
            List<InjectedItem> injected,
            List<RecalledLine> lines) {
        int idx = c.scenario.sessionIndex(probe.after());
        for (Fact f : c.scenario.liveFacts(idx)) {
            if (f.relevantTo().contains(probe.id())
                    && c.scenario.visibleTo(f, probe.userId(), probe.tenantId(), idx)) {
                injected.add(
                        new InjectedItem(
                                f.id(), f.key(), f.value(), probe.userId(), probe.tenantId()));
                lines.add(new RecalledLine(f.value(), "oracle"));
            }
        }
    }

    /**
     * One recalled id as the scorers want it: the row is looked up in every schema of the case, so
     * the user and tenant are where the row REALLY lives (a row of another tenant is reported under
     * that tenant), not where it was asked for.
     */
    InjectedItem resolve(PlatformEvalSubject.Case c, Probe probe, String preferredTable, UUID id) {
        List<String> tables = new ArrayList<>(List.of(preferredTable));
        for (String t : List.of("agent_memory", "tenant_memory", "memory_item")) {
            if (!tables.contains(t)) {
                tables.add(
                        t); // unified recall also serves legacy facts (the semantic source), so an
                // id may live in any of them
            }
        }
        for (String table : tables) {
            String userColumn = "memory_item".equals(table) ? "subject_keys[1]" : "subject_key";
            for (Map.Entry<String, String> e : c.schemaByTenant.entrySet()) {
                List<Map<String, Object>> rows =
                        owner.world.jdbc.queryForList(
                                "SELECT "
                                        + userColumn
                                        + " AS subject, content FROM "
                                        + e.getValue()
                                        + "."
                                        + table
                                        + " WHERE id = ?",
                                id);
                if (rows.isEmpty()) {
                    continue;
                }
                Object subject = rows.get(0).get("subject");
                PlatformEvalSubject.RowInfo info = c.rows.get(id);
                String key =
                        info != null
                                ? info.key()
                                : "memory_item".equals(table) ? "episode" : "unknown";
                return new InjectedItem(
                        info == null ? null : info.factId(),
                        key,
                        String.valueOf(rows.get(0).get("content")),
                        c.env.identity().logicalUser(subject == null ? null : subject.toString()),
                        e.getKey());
            }
        }
        owner.diagnostics.note(
                "recalled id "
                        + id
                        + " is in no memory table of the case (synthetic item, e.g. the self-model line)");
        return new InjectedItem(
                null, PlatformEvalSubject.UNANSWERED, "", probe.userId(), probe.tenantId());
    }

    // ------------------------------------------------------------------ store

    List<StoreItem> snapshot(PlatformEvalSubject.Case c) {
        List<StoreItem> out = new ArrayList<>();
        for (Map.Entry<String, String> e : c.schemaByTenant.entrySet()) {
            String schema = e.getValue();
            add(
                    out,
                    c,
                    e.getKey(),
                    owner.world.jdbc.queryForList(
                            "SELECT id, subject_key AS subject, content FROM "
                                    + schema
                                    + ".agent_memory ORDER BY created_at, id"),
                    "fact");
            add(
                    out,
                    c,
                    e.getKey(),
                    owner.world.jdbc.queryForList(
                            "SELECT id, subject_key AS subject, content FROM "
                                    + schema
                                    + ".tenant_memory"
                                    + " WHERE position('#mem:' in subject_key) = 0 ORDER BY created_at, id"),
                    "shared");
            add(
                    out,
                    c,
                    e.getKey(),
                    owner.world.jdbc.queryForList(
                            "SELECT id, subject_keys[1] AS subject, content FROM "
                                    + schema
                                    + ".memory_item ORDER BY created_at, id"),
                    "episode");
        }
        return out;
    }

    void add(
            List<StoreItem> out,
            PlatformEvalSubject.Case c,
            String logicalTenant,
            List<Map<String, Object>> rows,
            String kind) {
        for (Map<String, Object> row : rows) {
            UUID id = UUID.fromString(row.get("id").toString());
            PlatformEvalSubject.RowInfo info = c.rows.get(id);
            Object subject = row.get("subject");
            out.add(
                    new StoreItem(
                            info == null ? null : info.factId(),
                            info == null ? kind : info.key(),
                            String.valueOf(row.get("content")),
                            c.env.identity()
                                    .logicalUser(subject == null ? null : subject.toString()),
                            logicalTenant,
                            false));
        }
    }

    // ------------------------------------------------------------------ small helpers

    static String json(String... keyValues) {
        try {
            Map<String, String> map = new LinkedHashMap<>();
            for (int i = 0; i + 1 < keyValues.length; i += 2) {
                map.put(keyValues[i], keyValues[i + 1]);
            }
            return PlatformEvalSubject.JSON.writeValueAsString(map);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    static UUID memoryId(String toolResult) {
        try {
            JsonNode node = PlatformEvalSubject.JSON.readTree(toolResult);
            return node.hasNonNull("memoryId")
                    ? UUID.fromString(node.get("memoryId").asText())
                    : null;
        } catch (com.fasterxml.jackson.core.JsonProcessingException | IllegalArgumentException e) {
            return null;
        }
    }

    static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
