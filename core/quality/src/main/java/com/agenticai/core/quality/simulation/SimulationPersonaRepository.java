package com.agenticai.core.quality.simulation;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SimulationPersonaRepository extends JpaRepository<SimulationPersona, java.util.UUID> {

    List<SimulationPersona> findAllByOrderByNameAsc();

    Optional<SimulationPersona> findByKey(String key);

    List<SimulationPersona> findByKeyIn(List<String> keys);
}
