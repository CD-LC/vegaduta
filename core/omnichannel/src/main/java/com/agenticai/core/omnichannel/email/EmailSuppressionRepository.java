package com.agenticai.core.omnichannel.email;

import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface EmailSuppressionRepository extends JpaRepository<EmailSuppression, UUID> {

    boolean existsByEmail(String email);

    List<EmailSuppression> findByEmailIn(Collection<String> emails);
}
