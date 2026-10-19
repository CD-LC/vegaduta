package com.agenticai.core.rl.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Create body for {@code POST /api/rl/agents/{agentId}/policies/{dimension}/arms}.
 *
 * <p>{@code armKey} is the operator-facing name of the arm and must be unique
 * within the policy (it is what {@code PolicyStore.findArm} resolves rewards
 * by). {@code armConfigJson} is the raw config the runtime reads when this arm
 * wins a turn - it must be a JSON <em>object</em>, and for {@code MODEL_ROUTE}
 * it must carry a {@code "model"} string, because that is literally all {@code
 * AgentChatService} reads out of it. {@code contextBucket} is optional (null =
 * the global bucket, which is the only bucket the chat path currently requests).
 *
 * <p>Column limits mirror {@code policy_arm}: {@code arm_key varchar(128)},
 * {@code context_bucket varchar(64)} - checked here so an over-long value is a
 * clear 400 rather than a database constraint violation surfacing as a 500.
 */
public record PolicyArmRequest(
    @NotBlank @Size(max = 128) String armKey,
    @NotBlank String armConfigJson,
    @Size(max = 64) String contextBucket
) {
}
