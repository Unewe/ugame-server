package com.ugame.meta.matchmaking;

import com.ugame.meta.config.UgameProperties;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * Выбор игрового сервера для нового матча. Игровые серверы раз в пару секунд пишут
 * heartbeat {@code ugame:gs:<id> = host:port:rooms} с TTL; берём наименее загруженный.
 */
@Component
public class GameServerDirectory {
    private static final Logger log = LoggerFactory.getLogger(GameServerDirectory.class);

    public record GameServerAddress(String host, int port) {}

    private final StringRedisTemplate redis;
    private final UgameProperties.Matchmaking props;

    public GameServerDirectory(StringRedisTemplate redis, UgameProperties props) {
        this.redis = redis;
        this.props = props.matchmaking();
    }

    public GameServerAddress pick() {
        // KEYS допустим, пока серверов единицы; при росте — держать их в отдельном sorted set.
        Set<String> keys = redis.keys(RedisKeys.GAME_SERVER_PATTERN);
        GameServerAddress best = null;
        int bestRooms = Integer.MAX_VALUE;
        if (keys != null) {
            for (String key : keys) {
                String value = redis.opsForValue().get(key);
                if (value == null) {
                    continue; // TTL истёк между KEYS и GET
                }
                String[] parts = value.split(":");
                if (parts.length != 3) {
                    continue;
                }
                try {
                    int rooms = Integer.parseInt(parts[2]);
                    if (rooms < bestRooms) {
                        bestRooms = rooms;
                        best = new GameServerAddress(parts[0], Integer.parseInt(parts[1]));
                    }
                } catch (NumberFormatException e) {
                    log.warn("Некорректный heartbeat {}: {}", key, value);
                }
            }
        }
        if (best == null) {
            best = new GameServerAddress(props.fallbackServerHost(), props.fallbackServerPort());
        }
        return best;
    }
}
