package com.karandev.distributed_design_forge.intelligence_service.repository;

import com.karandev.distributed_design_forge.intelligence_service.entity.ChatSession;
import com.karandev.distributed_design_forge.intelligence_service.entity.ChatSessionId;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ChatSessionRepository extends JpaRepository<ChatSession, ChatSessionId> {
}
