package com.agenticai.core.quality.memeval;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * What a subject that talks to a real platform needs besides the scenario, handed to it by
 * {@link MemoryEvalRunner} (scorecard doc section 5 and Addendum D): an isolated identity for this
 * (case, arm, repeat), the clock to advance, and the memory switches of the arm.
 *
 * @param identity fresh tenant and user ids for this execution
 * @param clock    advanced by each session's {@code gap_days} before the session is played
 * @param switches recall and write switches implied by the arm
 */
public record CaseEnvironment(CaseIdentity identity, EvalClock clock, MemorySwitches switches) {

    public static CaseEnvironment of(MemoryScenario scenario, MemoryArm arm, int repeat) {
        return new CaseEnvironment(
            CaseIdentity.of(scenario.id(), arm, repeat), new EvalClock(), MemorySwitches.forArm(arm));
    }

    /**
     * Maps the scenario's logical ids ({@code u1}, {@code t1}) to physical ids that are unique to one
     * (scenario, arm, repeat), so no state can bleed between executions, and back again so the
     * scorers keep working on logical ids. {@link #logicalUser} / {@link #logicalTenant} return an id
     * that does not carry this execution's prefix unchanged, so a foreign id surfacing in a recall
     * trace still compares unequal to the asker and is scored as a leak.
     */
    public record CaseIdentity(String runKey) {

        public static CaseIdentity of(String scenarioId, MemoryArm arm, int repeat) {
            try {
                byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest((scenarioId + "|" + arm + "|" + repeat).getBytes(StandardCharsets.UTF_8));
                return new CaseIdentity("ev" + HexFormat.of().formatHex(digest, 0, 6));
            } catch (NoSuchAlgorithmException e) {
                throw new IllegalStateException("SHA-256 is mandatory on every JVM", e);
            }
        }

        public String tenantId(String logical) {
            return runKey + "-" + logical;
        }

        public String userId(String logical) {
            return runKey + "-" + logical;
        }

        public String logicalTenant(String physical) {
            return strip(physical);
        }

        public String logicalUser(String physical) {
            return strip(physical);
        }

        private String strip(String physical) {
            String prefix = runKey + "-";
            return physical != null && physical.startsWith(prefix) ? physical.substring(prefix.length()) : physical;
        }
    }

    /**
     * The two switches the A/B needs. The B arm must run the same agent with memory off, byte for byte
     * (doc section 5); the ORACLE arm retrieves nothing either, the subject puts the ledger facts
     * straight into the prompt. Platform names: {@code memory.recall.enabled} (behaviour setting, per
     * tenant and agent) is the recall switch; a write switch does not exist yet (Addendum D).
     */
    public record MemorySwitches(boolean recall, boolean write) {

        public static MemorySwitches forArm(MemoryArm arm) {
            boolean on = arm == MemoryArm.M;
            return new MemorySwitches(on, on);
        }
    }
}
