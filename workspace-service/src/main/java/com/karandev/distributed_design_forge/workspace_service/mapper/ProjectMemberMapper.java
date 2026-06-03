package com.karandev.distributed_design_forge.workspace_service.mapper;

import com.karandev.distributed_design_forge.workspace_service.dto.member.MemberResponse;
import com.karandev.distributed_design_forge.workspace_service.entity.ProjectMember;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper(componentModel = "spring")
public interface ProjectMemberMapper {

    @Mapping(target = "userId", source = "id.userId")
    MemberResponse toProjectMemberResponseFromMember(ProjectMember projectMember);
}
