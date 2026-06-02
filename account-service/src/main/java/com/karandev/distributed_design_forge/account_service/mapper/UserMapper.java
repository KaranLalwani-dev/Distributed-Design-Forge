package com.karandev.distributed_design_forge.account_service.mapper;


import com.karandev.distributed_design_forge.account_service.dto.auth.SignupRequest;
import com.karandev.distributed_design_forge.account_service.dto.auth.UserProfileResponse;
import com.karandev.distributed_design_forge.account_service.entity.User;
import com.karandev.distributed_design_forge.common_lib.dto.UserDto;
import com.karandev.distributed_design_forge.common_lib.security.JwtUserPrincipal;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper(componentModel = "spring")
public interface UserMapper {

    User toEntity(SignupRequest signupRequest);

    @Mapping(source = "userId", target = "id")
    UserProfileResponse toUserProfileResponse(JwtUserPrincipal user);

    UserDto toUserDto(User user);

}
