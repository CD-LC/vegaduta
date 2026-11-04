package com.agenticai.core.quality.memeval;

import com.agenticai.core.quality.memeval.InMemoryReferenceSubject.Quirk;
import com.agenticai.core.quality.memeval.MemoryScorecardGate.Context;
import com.agenticai.core.quality.memeval.MemoryScorecardGate.Report;
import com.agenticai.core.quality.memeval.MemoryScorecardGate.Tier;
import com.agenticai.core.quality.memeval.MemoryScorer.ScenarioScore;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Pipeline and command line of the memory eval: run a subject over the dataset in all three arms,
 * score, build the scorecard, evaluate the gates and write {@code memory-scorecard.json} in the shape
 * of Addendum A (docs/architecture/phase1/memory-scorecard.schema.json) that
 * {@code scripts/ci/memory-scorecard-gate.sh} reads.
 *
 * <p>Usage (from core/quality): {@code mvn -o exec:java
 * -Dexec.mainClass=com.agenticai.core.quality.memeval.MemoryEvalCli -Dexec.args="--subject good"}.
 * Options: {@code --subject good|bad}, {@code --repeats N} (3), {@code --tier ci|nightly} (ci),
 * {@code --default-on}, {@code --seed N}, {@code --out PATH}
 * (default {@code target/memory-eval/memory-scorecard.json}). Exit code: 0 SHIP, 3 SHIP DARK, 1 NO-SHIP.
 * Only the in-memory reference subject runs here; the runner against the platform is a separate work package.
 */
public final class MemoryEvalCli {

    public static final String DEFAULT_OUT = "target/memory-eval/memory-scorecard.json";

    private static final ObjectMapper JSON = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    private MemoryEvalCli() {}

    /** Scorecard and verdict of one pipeline run. */
    public record Result(MemoryScorecard scorecard, Report report) {}

    public static Result run(
        MemoryEvalSubject subject, List<MemoryScenario> scenarios, int repeats, long seed, Context ctx
    ) {
        List<MemoryRun> runs = MemoryEvalRunner.runAll(
            subject, scenarios, List.of(MemoryArm.B, MemoryArm.M, MemoryArm.ORACLE), repeats, seed);
        java.util.Map<String, MemoryScenario> byId = new java.util.HashMap<>();
        scenarios.forEach(s -> byId.put(s.id(), s));
        List<ScenarioScore> scores = new ArrayList<>();
        for (MemoryRun r : runs) {
            scores.add(MemoryScorer.score(byId.get(r.scenarioId()), r));
        }
        MemoryScorecard card = MemoryScorecards.build(subject.name(), scores, seed);
        return new Result(card, MemoryScorecardGate.evaluate(card, ctx));
    }

    /** Addendum A document plus the full scorecard under an extra {@code scorecard} key (ignored by the script). */
    public static String toJson(Result result, Instant generatedAt) {
        ObjectNode root = JSON.createObjectNode();
        root.put("schemaVersion", 1);
        root.put("generatedAt", generatedAt.toString());
        root.put("verdict", result.report().verdict().label());
        root.put("memoryDefaultOn", result.report().memoryDefaultOn());
        root.put("tier", result.report().tier().name());
        ArrayNode gates = root.putArray("gates");
        for (MemoryScorecardGate.GateResult g : result.report().gates()) {
            ObjectNode n = gates.addObject();
            n.put("id", g.id());
            n.put("name", g.name());
            n.put("status", g.status().name());
            n.put("detail", g.detail());
        }
        root.set("scorecard", JSON.valueToTree(result.scorecard()));
        try {
            return JSON.writeValueAsString(root);
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    public static int exitCode(Report report) {
        return switch (report.verdict()) {
            case SHIP -> 0;
            case SHIP_DARK -> 3;
            case NO_SHIP -> 1;
        };
    }

    /** The deliberately bad fixture: leaks canaries, ignores forget, keeps stale facts, leaks across users. */
    public static MemoryEvalSubject badSubject() {
        return InMemoryReferenceSubject.with("reference-bad", Quirk.PERSISTS_ATTACKS, Quirk.OBEYS_INJECTED_RULES,
            Quirk.IGNORES_FORGET, Quirk.KEEPS_STALE_FACTS, Quirk.LEAKS_ACROSS_USERS, Quirk.LEAKS_ACROSS_TENANTS);
    }

    public static void main(String[] args) throws IOException {
        String subject = "good";
        int repeats = 3;
        Tier tier = Tier.CI;
        boolean defaultOn = false;
        long seed = 20261104L;
        Path out = Path.of(DEFAULT_OUT);
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--subject" -> subject = args[++i];
                case "--repeats" -> repeats = Integer.parseInt(args[++i]);
                case "--tier" -> tier = Tier.valueOf(args[++i].toUpperCase(java.util.Locale.ROOT));
                case "--default-on" -> defaultOn = true;
                case "--seed" -> seed = Long.parseLong(args[++i]);
                case "--out" -> out = Path.of(args[++i]);
                default -> throw new IllegalArgumentException("unknown option " + args[i]);
            }
        }
        MemoryEvalSubject s = subject.equals("bad") ? badSubject() : InMemoryReferenceSubject.good();
        Context ctx = (tier == Tier.CI ? Context.ci() : Context.nightly()).withDefaultOn(defaultOn);
        Result result = run(s, MemoryScenarioLoader.loadAll(), repeats, seed, ctx);
        if (out.getParent() != null) {
            Files.createDirectories(out.getParent());
        }
        Files.writeString(out, toJson(result, Instant.now()));
        System.out.println("memory eval verdict: " + result.report().verdict().label() + " -> " + out);
        for (MemoryScorecardGate.GateResult g : result.report().gates()) {
            System.out.println("  " + g.id() + " " + g.status() + " " + g.detail());
        }
        System.exit(exitCode(result.report()));
    }
}
