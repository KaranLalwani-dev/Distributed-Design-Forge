package com.karandev.distributed_design_forge.workspace_service.dto.member;

import com.karandev.distributed_design_forge.common_lib.enums.ProjectRole;
import jakarta.validation.constraints.NotNull;

public record UpdateMemberRoleRequest(
        @NotNull ProjectRole role) {
}
