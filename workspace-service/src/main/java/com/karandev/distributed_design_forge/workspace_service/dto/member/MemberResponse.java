package com.karandev.distributed_design_forge.workspace_service.dto.member;


import com.karandev.distributed_design_forge.common_lib.enums.ProjectRole;

import java.time.Instant;

public record MemberResponse(
        Long userId,
        String username,
        String name,
        ProjectRole projectRole,
        Instant invitedAt
) {
}
