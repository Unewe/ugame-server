package com.ugame.meta.auth;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** Тела запросов и ответов /api/auth/*. */
public final class AuthDtos {
    private AuthDtos() {}

    public record RegisterRequest(
            @NotBlank @Pattern(regexp = "^[A-Za-z0-9_]{3,16}$",
                    message = "3–16 символов: латиница, цифры, _") String username,
            @Email @Size(max = 254) String email,
            @NotBlank @Size(min = 8, max = 72, message = "от 8 до 72 символов") String password) {}

    public record LoginRequest(@NotBlank String username, @NotBlank String password) {}

    public record RefreshRequest(@NotBlank String refreshToken) {}

    /**
     * @param expiresIn срок жизни access-токена в секундах
     */
    public record TokenResponse(String accessToken, String refreshToken, long expiresIn, long playerId,
                                String username) {}
}
