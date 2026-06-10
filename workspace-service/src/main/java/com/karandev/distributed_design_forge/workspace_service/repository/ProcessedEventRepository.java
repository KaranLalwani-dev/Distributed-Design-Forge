package com.karandev.distributed_design_forge.workspace_service.repository;

import com.karandev.distributed_design_forge.workspace_service.entity.ProcessedEvent;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProcessedEventRepository extends JpaRepository<ProcessedEvent, String> {
}
