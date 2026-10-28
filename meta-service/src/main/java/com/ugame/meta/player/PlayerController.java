package com.ugame.meta.player;

import com.ugame.meta.web.ApiException;
import java.time.Instant;
import java.util.Objects;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class PlayerController {

    private final PlayerRepository players;

    public PlayerController(PlayerRepository players) {
        this.players = players;
    }

    public record ProfileResponse(long id, String username, String email, int trophies, int matchesPlayed, int wins,
                                  Instant createdAt) {}

    @GetMapping("/me")
    public ProfileResponse me(@AuthenticationPrincipal Jwt jwt) {
        Player p = players.findById(Long.parseLong(Objects.requireNonNull(jwt.getSubject())))
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "player_not_found", "Игрок не найден"));
        return new ProfileResponse(p.getId(), p.getUsername(), p.getEmail(), p.getTrophies(),
                p.getMatchesPlayed(), p.getWins(), p.getCreatedAt());
    }
}
