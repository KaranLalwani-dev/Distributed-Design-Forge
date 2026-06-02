package com.karandev.distributed_design_forge.workspace_service.dto.project;


public record FileNode(
        String path
) {

    @Override
    public String toString() {
        return path;
    }
}
