package com.agenticai.core.quality.memeval;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * One memory-eval scenario: a persona, scripted multi-session turns, the ground-truth fact ledger
 * and the probes that are scored against it (scorecard doc section 4). Pure data; loaded from JSON by
 * {@link MemoryScenarioLoader}. JSON keys are snake_case ({@code gap_days}, {@code planted_in}).
 *
 * <p>Deviation from the doc's flat probe shape: the expectations are grouped under {@code expect}
 * so no record exceeds the 12-constructor-parameter architecture rule.
 */
public record MemoryScenario(
    int schemaVersion,
    String id,
    String category,
    Persona persona,
    long seed,
    List<Session> sessions,
    List<Fact> ledger,
    List<Probe> probes,
    Attack attack
) {

    public static final int SCHEMA_VERSION = 1;

    public MemoryScenario {
        sessions = sessions == null ? List.of() : List.copyOf(sessions);
        ledger = ledger == null ? List.of() : List.copyOf(ledger);
        probes = probes == null ? List.of() : List.copyOf(probes);
    }

    public record Persona(String id, String name, String language, String description) {}

    /** How a fact entered the conversation; anything but USER is untrusted provenance. */
    public enum TurnSource { USER, TOOL_OUTPUT, DOCUMENT, AGENT_MESSAGE }

    public enum FactKind { FACT, PREFERENCE, CORRECTION, RULE, ATTACK }

    public enum Sensitivity { PUBLIC, PERSONAL, PRIVATE, CRITICAL }

    public enum ProbeType {
        DIRECT_RECALL, PREFERENCE, UPDATE, MULTI_HOP, ABSTENTION, DISTRACTOR,
        MISTAKE, POISON, ISOLATION, FORGET, STORE_CHECK
    }

    /** How the fake agent acts on a probe task when it does / does not hold the relevant memory. */
    public enum ToolMode {
        /** The memory item for memory_key supplies the corrected argument. */
        REPLACE_ARG,
        /** A recalled rule makes the agent park for approval instead of calling the tool. */
        PARK,
        /** The platform always parks for approval; only an obeyed injected rule skips it. */
        GUARDED,
        /** The tool is called with default_arg; an obeyed injected rule appends its text. */
        PLAIN
    }

    /** A turn the scripted user sends. {@code plants} lists the ledger facts a perfect extractor captures. */
    public record Turn(TurnSource source, String text, List<String> plants) {
        public Turn {
            source = source == null ? TurnSource.USER : source;
            plants = plants == null ? List.of() : List.copyOf(plants);
        }
    }

    /**
     * A session; {@code gapDays} is the clock advance before it starts; {@code forgets} are fact ids
     * erased in it; {@code shares} are fact ids the session's user explicitly shares with the rest of
     * their tenant from here on (never with another tenant).
     */
    public record Session(
        String id, int gapDays, String userId, String tenantId, List<Turn> turns, List<String> forgets,
        List<String> shares
    ) {
        public Session {
            turns = turns == null ? List.of() : List.copyOf(turns);
            forgets = forgets == null ? List.of() : List.copyOf(forgets);
            shares = shares == null ? List.of() : List.copyOf(shares);
        }
    }

    /**
     * Ledger entry. {@code match} is the token looked for in answers (defaults to {@code value});
     * {@code ownerUser}/{@code ownerTenant} default to the planting session's. A fact is superseded
     * once the fact named by {@code supersededBy} has been planted.
     */
    public record Fact(
        String id, String key, String value, String match, String plantedIn, int validFrom,
        String supersededBy, Sensitivity sensitivity, List<String> relevantTo, FactKind kind,
        String ownerUser, String ownerTenant
    ) {
        public Fact {
            relevantTo = relevantTo == null ? List.of() : List.copyOf(relevantTo);
            kind = kind == null ? FactKind.FACT : kind;
            sensitivity = sensitivity == null ? Sensitivity.PERSONAL : sensitivity;
        }

        public String matchToken() {
            return match == null || match.isBlank() ? value : match;
        }
    }

    public record ToolSpec(String name, String argContains) {}

    /** What the fake agent would do for this probe and which memory key changes it. */
    public record ToolTask(String tool, String defaultArg, String memoryKey, ToolMode mode) {
        public ToolTask {
            mode = mode == null ? ToolMode.PLAIN : mode;
        }
    }

    public record Expect(
        List<String> all, List<String> none, ToolSpec tool, List<ToolSpec> forbidTools,
        boolean abstain, Integer maxChars
    ) {
        public Expect {
            all = all == null ? List.of() : List.copyOf(all);
            none = none == null ? List.of() : List.copyOf(none);
            forbidTools = forbidTools == null ? List.of() : List.copyOf(forbidTools);
        }

        public static Expect empty() {
            return new Expect(null, null, null, null, false, null);
        }
    }

    /**
     * A scored question asked after session {@code after}. {@code queryKeys} are the memory topics the
     * retriever is expected to touch; {@code memoryDependent} marks probes answerable only with prior
     * sessions (the G1 subset); {@code storeCheck} = "single_live" asserts one live fact per query key.
     */
    public record Probe(
        String id, String after, ProbeType type, String prompt, String userId, String tenantId,
        List<String> queryKeys, boolean memoryDependent, String lmeAbility, ToolTask task,
        Expect expect, String storeCheck
    ) {
        public Probe {
            queryKeys = queryKeys == null ? List.of() : List.copyOf(queryKeys);
            expect = expect == null ? Expect.empty() : expect;
        }
    }

    /** Poisoning metadata: payload family, delivery channel and the canaries planted in the payload. */
    public record Attack(String family, String channel, List<String> canaries) {
        public Attack {
            canaries = canaries == null ? List.of() : List.copyOf(canaries);
        }
    }

    // ---- derived lookups (pure) ----

    public Map<String, Fact> factsById() {
        Map<String, Fact> out = new LinkedHashMap<>();
        for (Fact f : ledger) {
            out.put(f.id(), f);
        }
        return out;
    }

    public int sessionIndex(String sessionId) {
        for (int i = 0; i < sessions.size(); i++) {
            if (Objects.equals(sessions.get(i).id(), sessionId)) {
                return i;
            }
        }
        return -1;
    }

    /** Index of the session whose turns plant the fact, or -1 when no turn plants it. */
    public int plantedAtIndex(String factId) {
        for (int i = 0; i < sessions.size(); i++) {
            for (Turn t : sessions.get(i).turns()) {
                if (t.plants().contains(factId)) {
                    return i;
                }
            }
        }
        return -1;
    }

    /** Cumulative gap days up to and including the session at {@code index}. */
    public int dayOf(int index) {
        int day = 0;
        for (int i = 0; i <= index && i < sessions.size(); i++) {
            day += sessions.get(i).gapDays();
        }
        return day;
    }

    /** True when the fact's successor has been planted by the time of session {@code index}. */
    public boolean supersededBy(Fact f, int index) {
        if (f.supersededBy() == null) {
            return false;
        }
        int at = plantedAtIndex(f.supersededBy());
        return at >= 0 && at <= index;
    }

    /** True when the fact was erased by a forget in a session at or before {@code index}. */
    public boolean forgotten(Fact f, int index) {
        for (int i = 0; i <= index && i < sessions.size(); i++) {
            if (sessions.get(i).forgets().contains(f.id())) {
                return true;
            }
        }
        return false;
    }

    /** True when the owner explicitly shared the fact (tenant-wide) in a session at or before {@code index}. */
    public boolean sharedAt(Fact f, int index) {
        for (int i = 0; i <= index && i < sessions.size(); i++) {
            if (sessions.get(i).shares().contains(f.id())) {
                return true;
            }
        }
        return false;
    }

    /**
     * Whether {@code userId} in {@code tenantId} may see the fact at session {@code index}: its owner
     * always, anyone else in the owner's tenant only once the owner has shared it.
     */
    public boolean visibleTo(Fact f, String userId, String tenantId, int index) {
        if (Objects.equals(ownerTenant(f), tenantId)) {
            return Objects.equals(ownerUser(f), userId) || sharedAt(f, index);
        }
        return false;
    }

    public String ownerUser(Fact f) {
        if (f.ownerUser() != null) {
            return f.ownerUser();
        }
        int at = plantedAtIndex(f.id());
        return at < 0 ? null : sessions.get(at).userId();
    }

    public String ownerTenant(Fact f) {
        if (f.ownerTenant() != null) {
            return f.ownerTenant();
        }
        int at = plantedAtIndex(f.id());
        return at < 0 ? null : sessions.get(at).tenantId();
    }

    /** Facts planted, not superseded, not forgotten and not attack payloads at session {@code index}. */
    public List<Fact> liveFacts(int index) {
        List<Fact> out = new ArrayList<>();
        for (Fact f : ledger) {
            int at = plantedAtIndex(f.id());
            if (at >= 0 && at <= index && f.kind() != FactKind.ATTACK
                && !supersededBy(f, index) && !forgotten(f, index)) {
                out.add(f);
            }
        }
        return out;
    }
}
