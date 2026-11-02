package com.ugame.meta.auth;

import jakarta.validation.Valid;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService auth;

    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    public Mono<AuthDtos.TokenResponse> register(@Valid @RequestBody AuthDtos.RegisterRequest req) {
        return auth.register(req);
    }

    /** Новый гостевой аккаунт со сгенерированными именем и паролем. */
    @PostMapping("/guest")
    @ResponseStatus(HttpStatus.CREATED)
    public Mono<AuthDtos.GuestResponse> guest() {
        return auth.guest();
    }

    /** Гость задаёт своё имя, email и пароль; прогресс сохраняется. */
    @PostMapping("/upgrade")
    public Mono<AuthDtos.TokenResponse> upgrade(@AuthenticationPrincipal Jwt jwt,
                                                @Valid @RequestBody AuthDtos.RegisterRequest req) {
        return auth.upgrade(Long.parseLong(Objects.requireNonNull(jwt.getSubject())), req);
    }

    @PostMapping("/login")
    public Mono<AuthDtos.TokenResponse> login(@Valid @RequestBody AuthDtos.LoginRequest req) {
        return auth.login(req);
    }

    @PostMapping("/refresh")
    public Mono<AuthDtos.TokenResponse> refresh(@Valid @RequestBody AuthDtos.RefreshRequest req) {
        return auth.refresh(req);
    }

    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public Mono<Void> logout(@Valid @RequestBody AuthDtos.RefreshRequest req) {
        return auth.logout(req);
    }
}
