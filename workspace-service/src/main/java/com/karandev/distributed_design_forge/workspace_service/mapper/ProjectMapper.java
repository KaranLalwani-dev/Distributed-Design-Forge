package com.karandev.distributed_design_forge.workspace_service.mapper;

import com.karandev.distributed_design_forge.common_lib.enums.ProjectRole;
import com.karandev.distributed_design_forge.workspace_service.dto.project.ProjectResponse;
import com.karandev.distributed_design_forge.workspace_service.dto.project.ProjectSummaryResponse;
import com.karandev.distributed_design_forge.workspace_service.entity.Project;
import org.mapstruct.Mapper;

import java.util.List;

@Mapper(componentModel = "spring")
public interface ProjectMapper {

    ProjectResponse toProjectResponse(Project project);

    ProjectSummaryResponse toProjectSummaryResponse(Project project, ProjectRole role);

    List<ProjectSummaryResponse> toListOfProjectSummaryResponse(List<Project> projects);

}