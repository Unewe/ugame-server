package com.ugame.meta.matchmaking;

import com.ugame.meta.player.PlayerRepository;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** Применяет результат матча к профилям игроков. Идемпотентно: повторная доставка ничего не меняет. */
@Slf4j
@Service
@RequiredArgsConstructor
public class MatchResultService {
    static final int WIN_TROPHIES = 8;
    static final int LOSS_TROPHIES = -5;

    private final DatabaseClient db;
    private final PlayerRepository players;
    private final ReactiveStringRedisTemplate redis;
    private final TransactionalOperator tx;

    /** Строка одного игрока в поле results: playerId:team:kills:deaths. */
    record Line(long playerId, int team, int kills, int deaths) {}

    public Mono<Void> apply(String matchId, int winnerTeam, String results) {
        List<Line> lines = parse(results);

        Mono<Boolean> updateDb = db.sql("""
                        insert into match_results (match_id, winner_team, payload)
                        values (:matchId, :winnerTeam, :payload)
                        on conflict do nothing
                        """)
                .bind("matchId", matchId)
                .bind("winnerTeam", winnerTeam)
                .bind("payload", results == null ? "" : results)
                .fetch()
                .rowsUpdated()
                .flatMap(inserted -> inserted == 0
                        ? Mono.just(false) // матч уже обработан
                        : Flux.fromIterable(lines)
                                .concatMap(line -> applyLine(line, winnerTeam))
                                .then(Mono.just(true)))
                .as(tx::transactional); // запись о матче и счётчики — одной транзакцией

        return updateDb.flatMap(applied -> {
            if (!applied) {
                log.debug("Матч {} уже обработан", matchId);
                return Mono.<Void>empty();
            }
            // матч закончился — игроки снова могут искать игру
            return Flux.fromIterable(lines)
                    .concatMap(line -> redis.delete(RedisKeys.status(line.playerId())))
                    .then()
                    .doOnSuccess(v ->
                            log.info("Матч {}: результат применён, победила команда {}", matchId, winnerTeam));
        });
    }

    private Mono<Long> applyLine(Line line, int winnerTeam) {
        if (winnerTeam < 0) {
            return players.applyMatchResult(line.playerId(), 0, 0); // ничья
        }
        boolean won = winnerTeam == line.team();
        return players.applyMatchResult(line.playerId(), won ? 1 : 0, won ? WIN_TROPHIES : LOSS_TROPHIES);
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
