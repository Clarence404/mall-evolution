package io.github.clarence404.mall.user.auth;

import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
class AuthController {

    private final AuthService authService;

    @Operation(operationId = "login")
    @PostMapping("/api/auth/login")
    AuthService.TokenPair login(@RequestBody @Valid LoginRequest req) {
        return authService.login(req.phone(), req.password());
    }

    @Operation(operationId = "refreshToken")
    @PostMapping("/api/auth/refresh")
    AuthService.TokenPair refresh(@RequestBody @Valid RefreshRequest req) {
        return authService.refresh(req.refreshToken());
    }

    @Operation(operationId = "logout")
    @PostMapping("/api/auth/logout")
    ResponseEntity<Void> logout(@RequestBody @Valid RefreshRequest req) {
        authService.logout(req.refreshToken());
        return ResponseEntity.noContent().build();
    }

    record LoginRequest(@NotBlank String phone, @NotBlank String password) {}

    record RefreshRequest(@NotBlank String refreshToken) {}
}
