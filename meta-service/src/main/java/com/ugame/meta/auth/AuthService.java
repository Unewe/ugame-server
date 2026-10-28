package com.ugame.meta.auth;

import com.ugame.meta.player.Player;
import com.ugame.meta.player.PlayerRepository;
import com.ugame.meta.web.ApiException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuthService {

    private final PlayerRepository players;
    private final PasswordEncoder passwordEncoder;
    private final TokenService tokens;
    /** Хэш для сравнения, когда пользователя нет — чтобы время ответа не выдавало существование логина. */
    private final String dummyHash;

    public AuthService(PlayerRepository players, PasswordEncoder passwordEncoder, TokenService tokens) {
        this.players = players;
        this.passwordEncoder = passwordEncoder;
        this.tokens = tokens;
        this.dummyHash = passwordEncoder.encode("dummy-password-for-timing");
    }

    @Transactional
    public AuthDtos.TokenResponse register(AuthDtos.RegisterRequest req) {
        String email = req.email() == null || req.email().isBlank() ? null : req.email().trim();
        if (players.existsByUsernameIgnoreCase(req.username())) {
            throw new ApiException(HttpStatus.CONFLICT, "username_taken", "Имя уже занято");
        }
        if (email != null && players.existsByEmailIgnoreCase(email)) {
            throw new ApiException(HttpStatus.CONFLICT, "email_taken", "Email уже зарегистрирован");
        }
        Player player;
        try {
            player = players.saveAndFlush(new Player(req.username(), email, passwordEncoder.encode(req.password())));
        } catch (DataIntegrityViolationException e) {
            // гонка двух одновременных регистраций — сработал уникальный индекс
            throw new ApiException(HttpStatus.CONFLICT, "username_taken", "Имя уже занято");
        }
        return tokens.issue(player);
    }

    @Transactional(readOnly = true)
    public AuthDtos.TokenResponse login(AuthDtos.LoginRequest req) {
        Player player = players.findByUsernameIgnoreCase(req.username()).orElse(null);
        String hash = player != null ? player.getPasswordHash() : dummyHash;
        boolean ok = passwordEncoder.matches(req.password(), hash);
        if (player == null || !ok) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "invalid_credentials", "Неверное имя или пароль");
        }
        return tokens.issue(player);
    }

    @Transactional(readOnly = true)
    public AuthDtos.TokenResponse refresh(AuthDtos.RefreshRequest req) {
        long playerId = tokens.consumeRefreshToken(req.refreshToken())
                .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "invalid_refresh_token",
                        "Сессия истекла, войдите заново"));
        Player player = players.findById(playerId)
                .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "invalid_refresh_token",
                        "Сессия истекла, войдите заново"));
        return tokens.issue(player);
    }

    public void logout(AuthDtos.RefreshRequest req) {
        tokens.revoke(req.refreshToken());
    }
}
