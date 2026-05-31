package com.karandev.distributed_design_forge.account_service.dto.auth;

public record AuthResponse(
        String token,
        UserProfileResponse user
) {

}
