package com.karandev.distributed_design_forge.account_service.dto.auth;

public record UserProfileResponse(
        Long id,
        String username,
        String name
) {
}
