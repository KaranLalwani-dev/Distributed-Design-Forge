package com.karandev.distributed_design_forge.workspace_service.dto.project;

public record FileContentResponse(
        String path,
        String content
) {
}
