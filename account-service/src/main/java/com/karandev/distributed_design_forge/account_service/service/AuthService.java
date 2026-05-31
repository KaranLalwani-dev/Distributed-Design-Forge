package com.karandev.distributed_design_forge.account_service.service;


import com.karandev.distributed_design_forge.account_service.dto.auth.AuthResponse;
import com.karandev.distributed_design_forge.account_service.dto.auth.LoginRequest;
import com.karandev.distributed_design_forge.account_service.dto.auth.SignupRequest;

public interface AuthService {
    AuthResponse signup(SignupRequest request);

    AuthResponse login(LoginRequest request);
}
