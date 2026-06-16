package com.karandev.distributed_design_forge.account_service.service.impl;


import com.karandev.distributed_design_forge.account_service.dto.auth.AuthResponse;
import com.karandev.distributed_design_forge.account_service.dto.auth.LoginRequest;
import com.karandev.distributed_design_forge.account_service.dto.auth.SignupRequest;
import com.karandev.distributed_design_forge.account_service.entity.Subscription;
import com.karandev.distributed_design_forge.account_service.entity.User;
import com.karandev.distributed_design_forge.account_service.mapper.UserMapper;
import com.karandev.distributed_design_forge.account_service.repository.PlanRepository;
import com.karandev.distributed_design_forge.account_service.repository.SubscriptionRepository;
import com.karandev.distributed_design_forge.account_service.repository.UserRepository;
import com.karandev.distributed_design_forge.account_service.service.AuthService;
import com.karandev.distributed_design_forge.common_lib.enums.SubscriptionStatus;
import com.karandev.distributed_design_forge.common_lib.error.BadRequestException;
import com.karandev.distributed_design_forge.common_lib.security.AuthUtil;
import com.karandev.distributed_design_forge.common_lib.security.JwtUserPrincipal;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.experimental.FieldDefaults;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.ArrayList;

@Service
@RequiredArgsConstructor
@FieldDefaults(makeFinal = true, level = AccessLevel.PRIVATE)
public class AuthServiceImpl implements AuthService {

    UserRepository userRepository;
    UserMapper userMapper;
    PasswordEncoder passwordEncoder;
    AuthUtil authUtil;
    AuthenticationManager authenticationManager;
    PlanRepository planRepository;
    SubscriptionRepository subscriptionRepository;

    @Override
    public AuthResponse signup(SignupRequest request) {
        userRepository.findByUsername(request.username()).ifPresent(user -> {
            throw new BadRequestException("User already exists with username: "+request.username());
        });

        User user = userMapper.toEntity(request);
        user.setPassword(passwordEncoder.encode(request.password()));
        user = userRepository.save(user);
        Subscription subscription = Subscription.builder().user(user)
                .plan(planRepository.findById(1L).get())
                .cancelAtPeriodEnd(false)
                .status(SubscriptionStatus.ACTIVE)
                .build();
        subscriptionRepository.save(subscription);

        JwtUserPrincipal jwtUserPrincipal = new JwtUserPrincipal(user.getId(), user.getName(),
                user.getUsername(), null,  new ArrayList<>());

        String token = authUtil.generateAccessToken(jwtUserPrincipal);
        return new AuthResponse(token, userMapper.toUserProfileResponse(jwtUserPrincipal));
    }

    @Override
    public AuthResponse login(LoginRequest request) {
        Authentication authentication = authenticationManager.authenticate(
                new UsernamePasswordAuthenticationToken(request.username(), request.password())
        );

        JwtUserPrincipal user = (JwtUserPrincipal) authentication.getPrincipal();
        String token = authUtil.generateAccessToken(user);

        return new AuthResponse(token, userMapper.toUserProfileResponse(user));
    }
}
