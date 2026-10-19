package com.agenticai.core.guardrails.procedures;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProcedureRepository extends JpaRepository<Procedure, UUID> {

    List<Procedure> findAllByOrderByNameAsc();
}
