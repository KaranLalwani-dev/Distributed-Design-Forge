package com.karandev.distributed_design_forge.account_service.controller;


import com.karandev.distributed_design_forge.account_service.dto.auth.AuthResponse;
import com.karandev.distributed_design_forge.account_service.dto.auth.LoginRequest;
import com.karandev.distributed_design_forge.account_service.dto.auth.SignupRequest;
import com.karandev.distributed_design_forge.account_service.service.AuthService;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.experimental.FieldDefaults;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/auth")
@FieldDefaults(makeFinal = true, level = AccessLevel.PRIVATE)
public class AuthController {

    AuthService authService;
//    UserService userService;

    @PostMapping("/signup")
    public ResponseEntity<AuthResponse> signup(@RequestBody SignupRequest request) {
        return ResponseEntity.ok(authService.signup(request));
    }

    @PostMapping("/login")
    public ResponseEntity<AuthResponse> login(@RequestBody LoginRequest request) {
        return ResponseEntity.ok(authService.login(request));
    }

//    @GetMapping("/me")
//    public ResponseEntity<UserProfileResponse> getProfile() {
//        Long userId = 1L;
//        return ResponseEntity.ok(userService.getProfile(userId));
//    } TODO

}
