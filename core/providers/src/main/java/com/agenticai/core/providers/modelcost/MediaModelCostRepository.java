package com.agenticai.core.providers.modelcost;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MediaModelCostRepository extends JpaRepository<MediaModelCost, Long> {

    List<MediaModelCost> findAllByModalityAndEnabledTrue(String modality);
}
