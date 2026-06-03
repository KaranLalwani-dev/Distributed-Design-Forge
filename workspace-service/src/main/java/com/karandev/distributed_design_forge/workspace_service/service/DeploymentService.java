package com.karandev.distributed_design_forge.workspace_service.service;

import com.karandev.distributed_design_forge.workspace_service.dto.project.DeployResponse;
import org.jspecify.annotations.Nullable;

public interface DeploymentService {
    @Nullable DeployResponse deploy(Long projectId);
}

