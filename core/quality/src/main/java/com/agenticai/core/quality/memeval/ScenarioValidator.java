package com.agenticai.core.quality.memeval;

import com.agenticai.core.quality.memeval.MemoryScenario.Fact;
import com.agenticai.core.quality.memeval.MemoryScenario.Probe;
import com.agenticai.core.quality.memeval.MemoryScenario.ProbeType;
import com.agenticai.core.quality.memeval.MemoryScenario.Session;
import com.agenticai.core.quality.memeval.MemoryScenario.Turn;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Ledger consistency (scorecard doc section 8, T0): every reference resolves, supersede chains are
 * acyclic and stay on one key, every planted fact is planted exactly once, every probe queries real
 * ledger keys. Returns human-readable errors; an empty list means consistent.
 */
public final class ScenarioValidator {

    private ScenarioValidator() {}

    public static List<String> validate(MemoryScenario s) {
        List<String> errors = new ArrayList<>();
        String at = "scenario " + s.id() + ": ";
        if (s.id() == null || s.id().isBlank()) {
            errors.add("scenario has no id");
        }
        Set<String> sessionIds = new HashSet<>();
        Map<String, Integer> plantCount = new HashMap<>();
        for (Session ses : s.sessions()) {
            if (!sessionIds.add(ses.id())) {
                errors.add(at + "duplicate session id " + ses.id());
            }
            if (ses.gapDays() < 0) {
                errors.add(at + "session " + ses.id() + " has a negative gap_days");
            }
            for (Turn t : ses.turns()) {
                for (String p : t.plants()) {
                    plantCount.merge(p, 1, Integer::sum);
                }
            }
        }
        Map<String, Fact> byId = new HashMap<>();
        Set<String> keys = new HashSet<>();
        for (Fact f : s.ledger()) {
            if (byId.put(f.id(), f) != null) {
                errors.add(at + "duplicate fact id " + f.id());
            }
            keys.add(f.key());
        }
        Set<String> probeIds = new HashSet<>();
        for (Probe p : s.probes()) {
            if (!probeIds.add(p.id())) {
                errors.add(at + "duplicate probe id " + p.id());
            }
        }
        for (Fact f : s.ledger()) {
            int planted = plantCount.getOrDefault(f.id(), 0);
            if (planted != 1) {
                errors.add(
                        at + "fact " + f.id() + " is planted " + planted + " times (expected 1)");
            }
            if (f.plantedIn() == null || s.sessionIndex(f.plantedIn()) < 0) {
                errors.add(at + "fact " + f.id() + " planted_in unknown session " + f.plantedIn());
            } else if (s.plantedAtIndex(f.id()) != s.sessionIndex(f.plantedIn())) {
                errors.add(
                        at
                                + "fact "
                                + f.id()
                                + " planted_in disagrees with the turn that plants it");
            }
            for (String r : f.relevantTo()) {
                if (!probeIds.contains(r)) {
                    errors.add(at + "fact " + f.id() + " relevant_to unknown probe " + r);
                }
            }
            checkChain(s, f, byId, errors, at);
        }
        for (String id : plantCount.keySet()) {
            if (!byId.containsKey(id)) {
                errors.add(at + "a turn plants unknown fact " + id);
            }
        }
        for (Session ses : s.sessions()) {
            for (String id : ses.forgets()) {
                if (!byId.containsKey(id)) {
                    errors.add(at + "session " + ses.id() + " forgets unknown fact " + id);
                }
            }
        }
        for (int i = 0; i < s.sessions().size(); i++) {
            Session ses = s.sessions().get(i);
            for (String id : ses.shares()) {
                Fact shared = byId.get(id);
                if (shared == null) {
                    errors.add(at + "session " + ses.id() + " shares unknown fact " + id);
                } else if (s.plantedAtIndex(id) < 0 || s.plantedAtIndex(id) > i) {
                    errors.add(
                            at
                                    + "session "
                                    + ses.id()
                                    + " shares fact "
                                    + id
                                    + " before it is planted");
                } else if (!java.util.Objects.equals(s.ownerUser(shared), ses.userId())
                        || !java.util.Objects.equals(s.ownerTenant(shared), ses.tenantId())) {
                    errors.add(
                            at
                                    + "session "
                                    + ses.id()
                                    + " shares fact "
                                    + id
                                    + " that its user does not own");
                }
            }
        }
        for (Probe p : s.probes()) {
            checkProbe(s, p, keys, errors, at);
        }
        if (s.attack() != null) {
            if (s.attack().canaries().isEmpty()) {
                errors.add(at + "attack has no canary");
            }
            for (String c : s.attack().canaries()) {
                boolean planted =
                        s.ledger().stream().anyMatch(f -> EvalText.contains(f.value(), c));
                if (!planted) {
                    errors.add(at + "canary " + c + " is not in any ledger payload");
                }
            }
        }
        return errors;
    }

    private static void checkProbe(
            MemoryScenario s, Probe p, Set<String> keys, List<String> errors, String at) {
        if (s.sessionIndex(p.after()) < 0) {
            errors.add(at + "probe " + p.id() + " is after unknown session " + p.after());
        }
        if (p.type() == null) {
            errors.add(at + "probe " + p.id() + " has no type");
        }
        if (p.userId() == null || p.tenantId() == null) {
            errors.add(at + "probe " + p.id() + " needs user_id and tenant_id");
        }
        if (p.type() != ProbeType.ABSTENTION && p.type() != ProbeType.FORGET) {
            for (String k : p.queryKeys()) {
                if (!keys.contains(k)) {
                    errors.add(
                            at
                                    + "probe "
                                    + p.id()
                                    + " queries key '"
                                    + k
                                    + "' absent from the ledger");
                }
            }
        }
        if (p.expect().all().isEmpty()
                && p.expect().none().isEmpty()
                && p.expect().tool() == null
                && p.expect().forbidTools().isEmpty()
                && !p.expect().abstain()
                && p.storeCheck() == null) {
            errors.add(at + "probe " + p.id() + " asserts nothing");
        }
    }

    private static void checkChain(
            MemoryScenario s, Fact f, Map<String, Fact> byId, List<String> errors, String at) {
        Set<String> seen = new HashSet<>();
        Fact cur = f;
        while (cur.supersededBy() != null) {
            if (!seen.add(cur.id())) {
                errors.add(at + "supersede chain from " + f.id() + " is cyclic");
                return;
            }
            Fact next = byId.get(cur.supersededBy());
            if (next == null) {
                errors.add(
                        at
                                + "fact "
                                + cur.id()
                                + " superseded_by unknown fact "
                                + cur.supersededBy());
                return;
            }
            if (!next.key().equals(cur.key())) {
                errors.add(
                        at + "fact " + cur.id() + " is superseded by a fact with a different key");
            }
            if (s.plantedAtIndex(next.id()) < s.plantedAtIndex(cur.id())) {
                errors.add(
                        at
                                + "fact "
                                + next.id()
                                + " supersedes "
                                + cur.id()
                                + " but is planted earlier");
            }
            cur = next;
        }
    }

    /**
     * Dataset-level: scenario ids unique, canaries unique across scenarios, every scenario
     * consistent.
     */
    public static List<String> validateDataset(List<MemoryScenario> all) {
        List<String> errors = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        Set<String> canaries = new HashSet<>();
        for (MemoryScenario s : all) {
            if (!ids.add(s.id())) {
                errors.add("duplicate scenario id " + s.id());
            }
            errors.addAll(validate(s));
            if (s.attack() != null) {
                for (String c : s.attack().canaries()) {
                    if (!canaries.add(c)) {
                        errors.add("canary " + c + " is reused across scenarios");
                    }
                }
            }
        }
        return errors;
    }
}
