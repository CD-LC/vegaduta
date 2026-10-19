package com.agenticai.core.omnichannel.session;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ChannelDefaultAgentRepository extends JpaRepository<ChannelDefaultAgent, UUID> {

    Optional<ChannelDefaultAgent> findByChannel(ChannelType channel);
}
