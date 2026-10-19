package com.agenticai.core.rl.web.dto;

import com.agenticai.core.rl.domain.PolicyArm;
import java.util.UUID;

/**
 * One arm as the admin arm-management surface sees it - the fixed contract the
 * web arm editor is built against:
 * {@code { id, armKey, armConfigJson, contextBucket, pulls, alpha, beta }}.
 *
 * <p>Deliberately narrower than {@code RlSummaryResponse.ArmStat} (the
 * dashboard's read model, which also carries dimension/policyId/avgReward/
 * enabled): this one is scoped by its own URL to a single agent+dimension, so
 * repeating those would be noise. {@code pulls}/{@code alpha}/{@code beta} are
 * included because they are what tells an operator whether an arm has actually
 * been learned about yet - {@code alpha == beta == 1.0} with {@code pulls == 0}
 * is a brand-new arm carrying nothing but the uniform prior.
 */
public record PolicyArmResponse(
    UUID id,
    String armKey,
    String armConfigJson,
    String contextBucket,
    long pulls,
    double alpha,
    double beta
) {

    public static PolicyArmResponse from(PolicyArm arm) {
        return new PolicyArmResponse(
            arm.getId(),
            arm.getArmKey(),
            arm.getArmConfigJson(),
            arm.getContextBucket(),
            arm.getPulls(),
            arm.getAlpha(),
            arm.getBeta()
        );
    }
}
