package com.ugame.meta.matchmaking;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Клиент ставит себя в очередь и опрашивает статус (раз в 1–2 секунды). Когда state = MATCHED,
 * в ответе есть host/port игрового сервера и одноразовый ticket.
 */
@RestController
@RequestMapping("/api/matchmaking")
public class MatchmakingController {

    private final MatchmakingService matchmaking;

    public MatchmakingController(MatchmakingService matchmaking) {
        this.matchmaking = matchmaking;
    }

    @PostMapping("/queue")
    public MatchmakingService.Status enqueue(@AuthenticationPrincipal Jwt jwt) {
        return matchmaking.enqueue(playerId(jwt));
    }

    @DeleteMapping("/queue")
    public MatchmakingService.Status cancel(@AuthenticationPrincipal Jwt jwt) {
        matchmaking.cancel(playerId(jwt));
        return matchmaking.status(playerId(jwt));
    }

    @GetMapping("/status")
    public MatchmakingService.Status status(@AuthenticationPrincipal Jwt jwt) {
        return matchmaking.status(playerId(jwt));
    }

    private static long playerId(Jwt jwt) {
        return Long.parseLong(jwt.getSubject());
    }
}
