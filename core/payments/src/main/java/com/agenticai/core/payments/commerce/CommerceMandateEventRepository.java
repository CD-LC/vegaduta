package com.agenticai.core.payments.commerce;

import com.agenticai.core.security.edition.ConditionalOnEditionFeature;
import com.agenticai.core.security.edition.EditionFeature;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
@ConditionalOnEditionFeature(EditionFeature.BILLING_PAYMENTS)
public interface CommerceMandateEventRepository extends JpaRepository<CommerceMandateEvent, Long> {

    List<CommerceMandateEvent> findByMandateIdOrderByIdAsc(UUID mandateId);
}
