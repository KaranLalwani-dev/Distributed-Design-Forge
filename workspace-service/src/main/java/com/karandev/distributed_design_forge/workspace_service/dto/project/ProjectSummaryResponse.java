package com.karandev.distributed_design_forge.workspace_service.dto.project;

import com.karandev.distributed_design_forge.common_lib.enums.ProjectRole;

import java.time.Instant;

public record ProjectSummaryResponse(
        Long id,
        String name,
        Instant createdAt,
        Instant updatedAt,
        ProjectRole role
) {
}
