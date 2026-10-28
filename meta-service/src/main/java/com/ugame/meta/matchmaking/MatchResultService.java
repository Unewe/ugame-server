package com.ugame.meta.matchmaking;

import com.ugame.meta.player.PlayerRepository;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Применяет результат матча к профилям игроков. Идемпотентно: повторная доставка ничего не меняет. */
@Service
public class MatchResultService {
    private static final Logger log = LoggerFactory.getLogger(MatchResultService.class);

    private final JdbcTemplate jdbc;
    private final PlayerRepository players;
    private final StringRedisTemplate redis;

    public MatchResultService(JdbcTemplate jdbc, PlayerRepository players, StringRedisTemplate redis) {
        this.jdbc = jdbc;
        this.players = players;
        this.redis = redis;
    }

    /** Строка одного игрока в поле results: playerId:team:kills:deaths. */
    record Line(long playerId, int team, int kills, int deaths) {}

    @Transactional
    public void apply(String matchId, int winnerTeam, String results) {
        int inserted = jdbc.update(
                "insert into match_results (match_id, winner_team, payload) values (?, ?, ?) on conflict do nothing",
                matchId, winnerTeam, results);
        if (inserted == 0) {
            log.debug("Матч {} уже обработан", matchId);
            return;
        }
        List<Line> lines = parse(results);
        for (Line line : lines) {
            players.findById(line.playerId()).ifPresent(p ->
                    p.applyMatchResult(winnerTeam == line.team(), winnerTeam < 0));
            // матч закончился — игрок снова может искать игру
            redis.delete(RedisKeys.status(line.playerId()));
        }
        log.info("Матч {}: результат применён, победила команда {}", matchId, winnerTeam);
    }

    static List<Line> parse(String results) {
        List<Line> lines = new ArrayList<>();
        if (results == null || results.isBlank()) {
            return lines;
        }
        for (String part : results.split(",")) {
            String[] f = part.split(":");
            if (f.length == 4) {
                lines.add(new Line(Long.parseLong(f[0]), Integer.parseInt(f[1]),
                        Integer.parseInt(f[2]), Integer.parseInt(f[3])));
            }
        }
        return lines;
    }
}
