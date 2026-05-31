package com.karandev.distributed_design_forge.account_service.mapper;


import com.karandev.distributed_design_forge.account_service.dto.auth.SignupRequest;
import com.karandev.distributed_design_forge.account_service.dto.auth.UserProfileResponse;
import com.karandev.distributed_design_forge.account_service.entity.User;
import com.karandev.distributed_design_forge.common_lib.dto.UserDto;
import org.mapstruct.Mapper;

@Mapper(componentModel = "spring")
public interface UserMapper {

    User toEntity(SignupRequest signupRequest);

    UserProfileResponse toUserProfileResponse(User user);

    UserDto toUserDto(User user);

}
