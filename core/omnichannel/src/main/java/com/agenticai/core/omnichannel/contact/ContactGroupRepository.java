package com.agenticai.core.omnichannel.contact;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ContactGroupRepository extends JpaRepository<ContactGroup, UUID> {

    /** Import rows may name a group rather than carry its UUID - lookup is case-insensitive so "vip" and "VIP" don't fork two groups. */
    Optional<ContactGroup> findByNameIgnoreCase(String name);
}
