package com.karandev.distributed_design_forge.workspace_service.service;

import com.karandev.distributed_design_forge.common_lib.dto.FileTreeDto;
import com.karandev.distributed_design_forge.workspace_service.dto.project.FileContentResponse;
import com.karandev.distributed_design_forge.workspace_service.dto.project.FileTreeResponse;

public interface ProjectFileService {
    FileTreeDto getFileTree(Long projectId);

    String getFileContent(Long projectId, String path);

    void saveFile(Long projectId, String filePath, String fileContent);
}


