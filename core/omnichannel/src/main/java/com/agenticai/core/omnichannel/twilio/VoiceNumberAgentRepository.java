package com.agenticai.core.omnichannel.twilio;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface VoiceNumberAgentRepository extends JpaRepository<VoiceNumberAgent, UUID> {

    List<VoiceNumberAgent> findAllByOrderByPhoneNumberAsc();

    Optional<VoiceNumberAgent> findByPhoneNumber(String phoneNumber);

    void deleteByPhoneNumber(String phoneNumber);
}
