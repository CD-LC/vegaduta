package com.agenticai.core.guardrails.procedures;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProcedureRuleRepository extends JpaRepository<ProcedureRule, UUID> {

    List<ProcedureRule> findByProcedureIdOrderByRuleOrderAsc(UUID procedureId);

    List<ProcedureRule> findByProcedureIdInOrderByProcedureIdAscRuleOrderAsc(List<UUID> procedureIds);

    void deleteByProcedureId(UUID procedureId);
}
