package com.karandev.distributed_design_forge.workspace_service.dto.project;

import com.karandev.distributed_design_forge.common_lib.dto.FileNode;

import java.util.List;

public record FileTreeResponse(List<FileNode> files) {
}
