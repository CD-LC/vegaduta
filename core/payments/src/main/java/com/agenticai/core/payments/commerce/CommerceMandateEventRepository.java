package com.agenticai.core.payments.commerce;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface CommerceMandateEventRepository extends JpaRepository<CommerceMandateEvent, Long> {

    List<CommerceMandateEvent> findByMandateIdOrderByIdAsc(UUID mandateId);
}
