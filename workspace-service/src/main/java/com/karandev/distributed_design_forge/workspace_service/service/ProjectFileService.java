package com.karandev.distributed_design_forge.workspace_service.service;

import com.karandev.distributed_design_forge.workspace_service.dto.project.FileContentResponse;
import com.karandev.distributed_design_forge.workspace_service.dto.project.FileTreeResponse;

public interface ProjectFileService {
    FileTreeResponse getFileTree(Long projectId);

    FileContentResponse getFileContent(Long projectId, String path);

    void saveFile(Long projectId, String filePath, String fileContent);
}
