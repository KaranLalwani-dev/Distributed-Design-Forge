package com.karandev.distributed_design_forge.workspace_service.service.Impl;

import com.karandev.distributed_design_forge.common_lib.dto.UserDto;
import com.karandev.distributed_design_forge.common_lib.error.ResourceNotFoundException;
import com.karandev.distributed_design_forge.common_lib.security.AuthUtil;
import com.karandev.distributed_design_forge.workspace_service.client.AccountClient;
import com.karandev.distributed_design_forge.workspace_service.dto.member.InviteMemberRequest;
import com.karandev.distributed_design_forge.workspace_service.dto.member.MemberResponse;
import com.karandev.distributed_design_forge.workspace_service.dto.member.UpdateMemberRoleRequest;
import com.karandev.distributed_design_forge.workspace_service.entity.Project;
import com.karandev.distributed_design_forge.workspace_service.entity.ProjectMember;
import com.karandev.distributed_design_forge.workspace_service.entity.ProjectMemberId;
import com.karandev.distributed_design_forge.workspace_service.mapper.ProjectMemberMapper;
import com.karandev.distributed_design_forge.workspace_service.repository.ProjectMemberRepository;
import com.karandev.distributed_design_forge.workspace_service.repository.ProjectRepository;
import com.karandev.distributed_design_forge.workspace_service.service.ProjectMemberService;
import jakarta.transaction.Transactional;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.experimental.FieldDefaults;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;

@Service
@FieldDefaults(makeFinal = true, level = AccessLevel.PRIVATE)
@RequiredArgsConstructor
@Transactional
public class ProjectMemberServiceImpl implements ProjectMemberService {

    ProjectMemberRepository projectMemberRepository;
    ProjectRepository projectRepository;
    ProjectMemberMapper projectMemberMapper;
    AuthUtil authUtil;
    AccountClient accountClient;

    @Override
    @PreAuthorize("@security.canViewMembers(#projectId)")
    public List<MemberResponse> getProjectMembers(Long projectId) {
        return projectMemberRepository.findByIdProjectId(projectId)
                .stream()
                .map(this::enrichMemberResponse)
                .toList();
    }

    @Override
    @PreAuthorize("@security.canManageMembers(#projectId)")
    public MemberResponse inviteMember(Long projectId, InviteMemberRequest request) {
        Long userId = authUtil.getCurrentUserId();
        Project project = getAccessibleProjectById(projectId, userId);

        UserDto invitee = accountClient.getUserByEmail(request.username()).orElseThrow(
                () -> new ResourceNotFoundException("User", request.username())
        );

        if(invitee.id().equals(userId)) {
            throw new RuntimeException("Cannot invite yourself");
        }

        ProjectMemberId projectMemberId = new ProjectMemberId(projectId, invitee.id());

        if(projectMemberRepository.existsById(projectMemberId)) {
            throw new RuntimeException("Cannot invite once again");
        }

        ProjectMember member = ProjectMember.builder()
                .id(projectMemberId)
                .project(project)
                .projectRole(request.role())
                .invitedAt(Instant.now())
                .build();

        projectMemberRepository.save(member);

        return enrichMemberResponse(member);
    }

    @Override
    @PreAuthorize("@security.canManageMembers(#projectId)")
    public MemberResponse updateMemberRole(Long projectId, Long memberId, UpdateMemberRoleRequest request) {
        Long userId = authUtil.getCurrentUserId();
        Project project = getAccessibleProjectById(projectId, userId);

        ProjectMemberId projectMemberId = new ProjectMemberId(projectId, memberId);
        ProjectMember projectMember = projectMemberRepository.findById(projectMemberId).orElseThrow();

        projectMember.setProjectRole(request.role());

        projectMemberRepository.save(projectMember);

        return enrichMemberResponse(projectMember);
    }

    @Override
    @PreAuthorize("@security.canManageMembers(#projectId)")
    public void removeProjectMember(Long projectId, Long memberId) {
        Long userId = authUtil.getCurrentUserId();
        Project project = getAccessibleProjectById(projectId, userId);

        ProjectMemberId projectMemberId = new ProjectMemberId(projectId, memberId);
        if(!projectMemberRepository.existsById(projectMemberId)) {
            throw new RuntimeException("Member not found in project");
        }

        projectMemberRepository.deleteById(projectMemberId);
    }

    public Project getAccessibleProjectById(Long projectId, Long userId) {
        return projectRepository.findAccessibleProjectById(projectId, userId).orElseThrow();
    }

    private MemberResponse enrichMemberResponse(ProjectMember member) {
        // Map the base fields that exist in the ProjectMember entity
        MemberResponse response = projectMemberMapper.toProjectMemberResponseFromMember(member);

        try {
            // Fetch the username and name from account-service
            UserDto user = accountClient.getUserById(member.getId().getUserId());

            // Construct a new record combining both
            return new MemberResponse(
                    response.userId(),
                    user.username(), // Injected
                    user.name(),     // Injected
                    response.projectRole(),
                    response.invitedAt()
            );
        } catch (Exception e) {
            // Return base response if account-service call fails
            return response;
        }
    }
}
