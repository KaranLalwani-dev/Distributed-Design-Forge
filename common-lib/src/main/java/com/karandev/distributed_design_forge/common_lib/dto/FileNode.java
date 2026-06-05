package com.karandev.distributed_design_forge.common_lib.dto;

public record FileNode(
        String path
) {

    @Override
    public String toString() {
        return path;
    }
}
