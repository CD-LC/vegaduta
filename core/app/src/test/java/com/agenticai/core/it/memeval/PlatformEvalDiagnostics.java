package com.agenticai.core.it.memeval;

import com.agenticai.core.quality.memeval.MemoryArm;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * What the real-platform run observed besides the scorecard: write outcomes (saved, quarantined, refused by the write
 * screen, skipped by the write switch), the scripted reviewer's decisions, and the overhead counters of the recall (time,
 * database statements, block tokens). Reported under the {@code platform} key of the scorecard JSON, per arm.
 */
public final class PlatformEvalDiagnostics {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static final class Arm {
        int writesSaved;
        int writesQuarantined;
        int writesRefused;
        int writesSkipped;
        int writesFailed;
        int episodesApproved;
        int proposalsHeld;
        int episodesDespiteWriteOff;
        final Map<String, int[]> attackWrites = new java.util.TreeMap<>();
        int recalls;
        int ingestionRecalls;
        int probes;
        long statements;
        long blockTokens;
        long recalledFacts;
        long injectedItems;
        final List<Double> recallMs = new ArrayList<>();
        final List<Double> probeRecallMs = new ArrayList<>();
        long probeStatements;
        int probeRecalls;
    }

    private final Map<MemoryArm, Arm> arms = new EnumMap<>(MemoryArm.class);
    private final List<String> notes = Collections.synchronizedList(new ArrayList<>());

    private synchronized Arm arm(MemoryArm a) {
        return arms.computeIfAbsent(a, k -> new Arm());
    }

    /** Classifies the JSON the {@code remember_fact} / {@code remember_shared_fact} tool returned. */
    public synchronized void write(MemoryArm a, String toolResult) {
        Arm arm = arm(a);
        try {
            JsonNode n = JSON.readTree(toolResult);
            if (n.hasNonNull("error")) {
                arm.writesRefused++;
            } else if (n.path("skipped").asBoolean(false) || toolResult.contains("skipped")) {
                arm.writesSkipped++;
            } else if (n.path("quarantined").asBoolean(false)) {
                arm.writesQuarantined++;
            } else if (n.path("remembered").asBoolean(false)) {
                arm.writesSaved++;
            } else {
                arm.writesFailed++;
            }
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            arm.writesFailed++;
        }
    }

    /** Outcome of an attack payload's write by delivery channel: [refused by the write screen, quarantined, saved TRUSTED, other]. */
    public synchronized void attackWrite(MemoryArm a, String channel, String toolResult) {
        int[] counts = arm(a).attackWrites.computeIfAbsent(channel, k -> new int[4]);
        try {
            JsonNode n = JSON.readTree(toolResult);
            if (n.hasNonNull("error")) {
                counts[0]++;
            } else if (n.path("quarantined").asBoolean(false)) {
                counts[1]++;
            } else if (n.path("remembered").asBoolean(false)) {
                counts[2]++;
            } else {
                counts[3]++;
            }
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            counts[3]++;
        }
    }

    public synchronized void episodeApproved(MemoryArm a) {
        arm(a).episodesApproved++;
    }

    public synchronized void episodeDespiteWriteSwitchOff(MemoryArm a) {
        arm(a).episodesDespiteWriteOff++;
    }

    public synchronized void proposalHeld(MemoryArm a) {
        arm(a).proposalsHeld++;
    }

    public synchronized void recall(MemoryArm a, double ms, long statements, long blockTokens, int recalledFacts, boolean ingestion) {
        Arm arm = arm(a);
        arm.recalls++;
        arm.statements += statements;
        arm.blockTokens += blockTokens;
        arm.recalledFacts += recalledFacts;
        arm.recallMs.add(ms);
        if (ingestion) {
            arm.ingestionRecalls++;
        } else {
            arm.probeRecalls++;
            arm.probeStatements += statements;
            arm.probeRecallMs.add(ms);
        }
    }

    public synchronized void probe(MemoryArm a, int injected) {
        Arm arm = arm(a);
        arm.probes++;
        arm.injectedItems += injected;
    }

    /** A run-level observation (first 40 kept, the rest only counted). */
    public void note(String text) {
        synchronized (notes) {
            if (notes.size() < 40) {
                notes.add(text);
            } else if (notes.size() == 40) {
                notes.add("... further notes dropped");
            }
        }
    }

    public synchronized ObjectNode toJson() {
        ObjectNode root = JSON.createObjectNode();
        ObjectNode byArm = root.putObject("arms");
        for (Map.Entry<MemoryArm, Arm> e : arms.entrySet()) {
            Arm a = e.getValue();
            ObjectNode n = byArm.putObject(e.getKey().name());
            n.put("writesSaved", a.writesSaved);
            n.put("writesQuarantined", a.writesQuarantined);
            n.put("writesRefusedByWriteScreen", a.writesRefused);
            n.put("writesSkippedByWriteSwitch", a.writesSkipped);
            n.put("writesFailed", a.writesFailed);
            n.put("episodesApprovedByScriptedReviewer", a.episodesApproved);
            n.put("proposalsHeldBackByScriptedReviewer", a.proposalsHeld);
            n.put("episodesRecordedWithWriteSwitchOff", a.episodesDespiteWriteOff);
            ObjectNode atk = n.putObject("attackPayloadWritesByChannel");
            a.attackWrites.forEach((ch, c) -> {
                ObjectNode o = atk.putObject(ch);
                o.put("refusedByWriteScreen", c[0]);
                o.put("quarantined", c[1]);
                o.put("savedTrusted", c[2]);
                o.put("other", c[3]);
            });
            n.put("recalls", a.recalls);
            n.put("probes", a.probes);
            n.put("meanBlockTokensPerRecall", a.recalls == 0 ? 0 : round(a.blockTokens / (double) a.recalls));
            n.put("meanRecalledFactsPerRecall", a.recalls == 0 ? 0 : round(a.recalledFacts / (double) a.recalls));
            n.put("meanInjectedItemsPerProbe", a.probes == 0 ? 0 : round(a.injectedItems / (double) a.probes));
            n.put("dbStatementsPerRecall", a.recalls == 0 ? 0 : round(a.statements / (double) a.recalls));
            n.put("dbStatementsPerProbeRecall", a.probeRecalls == 0 ? 0 : round(a.probeStatements / (double) a.probeRecalls));
            n.put("recallMsP50", round(percentile(a.recallMs, 0.50)));
            n.put("recallMsP95", round(percentile(a.recallMs, 0.95)));
            n.put("probeRecallMsP95", round(percentile(a.probeRecallMs, 0.95)));
        }
        ArrayNode ns = root.putArray("notes");
        notes.forEach(ns::add);
        return root;
    }

    private static double percentile(List<Double> values, double p) {
        if (values.isEmpty()) {
            return 0;
        }
        List<Double> sorted = new ArrayList<>(values);
        Collections.sort(sorted);
        return sorted.get((int) Math.min(sorted.size() - 1, Math.ceil(p * sorted.size()) - 1));
    }

    private static double round(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}
