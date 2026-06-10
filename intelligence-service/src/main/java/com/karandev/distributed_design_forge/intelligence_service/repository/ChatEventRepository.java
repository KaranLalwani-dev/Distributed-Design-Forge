package com.karandev.distributed_design_forge.intelligence_service.repository;

import com.karandev.distributed_design_forge.intelligence_service.entity.ChatEvent;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface ChatEventRepository extends JpaRepository<ChatEvent, Long> {
    Optional<ChatEvent> findBySagaId(String sagaId);
}

