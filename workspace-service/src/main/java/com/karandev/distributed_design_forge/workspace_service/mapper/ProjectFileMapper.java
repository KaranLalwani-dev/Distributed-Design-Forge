package com.karandev.distributed_design_forge.workspace_service.mapper;

import com.karandev.distributed_design_forge.common_lib.dto.FileNode;
import com.karandev.distributed_design_forge.workspace_service.entity.ProjectFile;
import org.mapstruct.Mapper;

import java.util.List;

@Mapper(componentModel = "spring")
public interface ProjectFileMapper {

    List<FileNode> toListOfFileNode(List<ProjectFile> projectFileList);
}
