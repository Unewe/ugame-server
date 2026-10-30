package com.ugame.meta.player;

import com.ugame.meta.web.ApiException;
import java.time.Instant;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class PlayerController {

    private final PlayerRepository players;

    public record ProfileResponse(long id, String username, String email, int trophies, int matchesPlayed, int wins,
                                  Instant createdAt) {

        static ProfileResponse of(Player p) {
            return new ProfileResponse(p.getId(), p.getUsername(), p.getEmail(), p.getTrophies(),
                    p.getMatchesPlayed(), p.getWins(), p.getCreatedAt());
        }
    }

    @GetMapping("/me")
    public Mono<ProfileResponse> me(@AuthenticationPrincipal Jwt jwt) {
        return players.findById(Long.parseLong(Objects.requireNonNull(jwt.getSubject())))
                .switchIfEmpty(Mono.error(() ->
                        new ApiException(HttpStatus.NOT_FOUND, "player_not_found", "Игрок не найден")))
                .map(ProfileResponse::of);
    }
}
