package com.ugame.meta.auth;

import com.ugame.meta.player.Player;
import com.ugame.meta.player.PlayerRepository;
import com.ugame.meta.web.ApiException;
import java.time.Instant;
import java.util.Optional;
import java.util.function.Supplier;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

@Service
@RequiredArgsConstructor
public class AuthService {

    private final PlayerRepository players;
    private final PasswordEncoder passwordEncoder;
    private final TokenService tokens;

    /**
     * Хэш для сравнения, когда пользователя нет, — чтобы время ответа не выдавало существование логина.
     * Lombok вычислит его при первом обращении и закэширует (потокобезопасно).
     */
    @Getter(value = AccessLevel.PRIVATE, lazy = true)
    private final String dummyHash = passwordEncoder.encode("dummy-password-for-timing");

    public Mono<AuthDtos.TokenResponse> register(AuthDtos.RegisterRequest req) {
        String email = req.email() == null || req.email().isBlank() ? null : req.email().trim();

        Mono<Void> checks = failIf(players.existsByUsernameIgnoreCase(req.username()), this::usernameTaken)
                .then(email == null ? Mono.<Void>empty()
                        : failIf(players.existsByEmailIgnoreCase(email),
                                () -> new ApiException(HttpStatus.CONFLICT, "email_taken", "Email уже зарегистрирован")));

        return checks
                .then(hash(req.password()))
                .flatMap(hash -> players.save(Player.builder()
                        .username(req.username())
                        .email(email)
                        .passwordHash(hash)
                        .createdAt(Instant.now())
                        .build()))
                // гонка двух одновременных регистраций — сработал уникальный индекс
                .onErrorMap(DataIntegrityViolationException.class, e -> usernameTaken())
                .flatMap(tokens::issue);
    }

    public Mono<AuthDtos.TokenResponse> login(AuthDtos.LoginRequest req) {
        return players.findByUsernameIgnoreCase(req.username())
                .map(Optional::of)
                .defaultIfEmpty(Optional.empty())
                .flatMap(player -> matches(req.password(),
                                () -> player.map(Player::getPasswordHash).orElseGet(this::getDummyHash))
                        .flatMap(ok -> ok && player.isPresent()
                                ? tokens.issue(player.get())
                                : Mono.error(() -> new ApiException(HttpStatus.UNAUTHORIZED,
                                        "invalid_credentials", "Неверное имя или пароль"))));
    }

    public Mono<AuthDtos.TokenResponse> refresh(AuthDtos.RefreshRequest req) {
        Supplier<ApiException> expired = () -> new ApiException(HttpStatus.UNAUTHORIZED,
                "invalid_refresh_token", "Сессия истекла, войдите заново");
        return tokens.consumeRefreshToken(req.refreshToken())
                .flatMap(players::findById)
                .switchIfEmpty(Mono.error(expired))
                .flatMap(tokens::issue);
    }

    public Mono<Void> logout(AuthDtos.RefreshRequest req) {
        return tokens.revoke(req.refreshToken());
    }

    // bcrypt намеренно медленный (~100 мс CPU) — уносим его с потоков event loop Netty.
    private Mono<String> hash(String password) {
        return Mono.fromCallable(() -> passwordEncoder.encode(password)).subscribeOn(Schedulers.boundedElastic());
    }

    private Mono<Boolean> matches(String password, Supplier<String> hash) {
        return Mono.fromCallable(() -> passwordEncoder.matches(password, hash.get()))
                .subscribeOn(Schedulers.boundedElastic());
    }

    private static Mono<Void> failIf(Mono<Boolean> condition, Supplier<ApiException> error) {
        return condition.flatMap(failed -> failed ? Mono.<Void>error(error.get()) : Mono.<Void>empty());
    }

    private ApiException usernameTaken() {
        return new ApiException(HttpStatus.CONFLICT, "username_taken", "Имя уже занято");
    }
}
