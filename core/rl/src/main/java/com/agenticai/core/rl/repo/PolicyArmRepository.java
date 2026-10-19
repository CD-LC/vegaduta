package com.agenticai.core.rl.repo;

import com.agenticai.core.rl.domain.PolicyArm;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PolicyArmRepository extends JpaRepository<PolicyArm, UUID> {

    List<PolicyArm> findByPolicyId(UUID policyId);

    List<PolicyArm> findByPolicyIdAndEnabledTrue(UUID policyId);

    Optional<PolicyArm> findFirstByPolicyIdAndArmKey(UUID policyId, String armKey);
}
