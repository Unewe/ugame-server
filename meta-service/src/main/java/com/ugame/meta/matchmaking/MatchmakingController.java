package com.ugame.meta.matchmaking;

import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/**
 * Клиент ставит себя в очередь и опрашивает статус (раз в 1–2 секунды). Когда state = MATCHED,
 * в ответе есть host/port игрового сервера и одноразовый ticket.
 */
@RestController
@RequestMapping("/api/matchmaking")
@RequiredArgsConstructor
public class MatchmakingController {

    private final MatchmakingService matchmaking;

    @PostMapping("/queue")
    public Mono<MatchmakingService.Status> enqueue(@AuthenticationPrincipal Jwt jwt) {
        return matchmaking.enqueue(playerId(jwt));
    }

    @DeleteMapping("/queue")
    public Mono<MatchmakingService.Status> cancel(@AuthenticationPrincipal Jwt jwt) {
        return matchmaking.cancel(playerId(jwt));
    }

    @GetMapping("/status")
    public Mono<MatchmakingService.Status> status(@AuthenticationPrincipal Jwt jwt) {
        return matchmaking.status(playerId(jwt));
    }

    private static long playerId(Jwt jwt) {
        return Long.parseLong(Objects.requireNonNull(jwt.getSubject()));
    }
}
