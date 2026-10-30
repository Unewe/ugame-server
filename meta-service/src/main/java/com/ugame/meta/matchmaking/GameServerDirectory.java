package com.ugame.meta.matchmaking;

import com.ugame.meta.config.UgameProperties;
import java.util.Comparator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * Выбор игрового сервера для нового матча. Игровые серверы раз в пару секунд пишут
 * heartbeat {@code ugame:gs:<id> = host:port:rooms} с TTL; берём наименее загруженный.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class GameServerDirectory {

    public record GameServerAddress(String host, int port) {}

    private record Candidate(GameServerAddress address, int rooms) {}

    private final ReactiveStringRedisTemplate redis;
    private final UgameProperties props;

    public Mono<GameServerAddress> pick() {
        UgameProperties.Matchmaking mm = props.matchmaking();
        // SCAN, а не KEYS: не блокирует Redis при большом числе ключей
        return redis.scan(ScanOptions.scanOptions().match(RedisKeys.GAME_SERVER_PATTERN).count(100).build())
                .flatMap(key -> redis.opsForValue().get(key).flatMap(value -> parse(key, value)))
                .sort(Comparator.comparingInt(Candidate::rooms))
                .next()
                .map(Candidate::address)
                .defaultIfEmpty(new GameServerAddress(mm.fallbackServerHost(), mm.fallbackServerPort()));
    }

    private static Mono<Candidate> parse(String key, String value) {
        String[] parts = value.split(":");
        try {
            if (parts.length == 3) {
                return Mono.just(new Candidate(
                        new GameServerAddress(parts[0], Integer.parseInt(parts[1])), Integer.parseInt(parts[2])));
            }
        } catch (NumberFormatException ignored) {
            // упадём в лог ниже
        }
        log.warn("Некорректный heartbeat {}: {}", key, value);
        return Mono.empty();
    }
}
