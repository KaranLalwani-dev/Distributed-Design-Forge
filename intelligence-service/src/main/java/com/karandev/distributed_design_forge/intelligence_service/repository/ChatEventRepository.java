package com.karandev.distributed_design_forge.intelligence_service.repository;

import com.karandev.distributed_design_forge.intelligence_service.entity.ChatEvent;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ChatEventRepository extends JpaRepository<ChatEvent, Long> {
}

