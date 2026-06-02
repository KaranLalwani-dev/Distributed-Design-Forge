package com.karandev.distributed_design_forge.workspace_service.dto.project;

import jakarta.validation.constraints.NotBlank;

public record ProjectRequest(
        @NotBlank String name
) {
}
