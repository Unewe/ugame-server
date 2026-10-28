package com.ugame.meta.matchmaking;

import com.ugame.meta.config.UgameProperties;
import com.ugame.meta.player.Player;
import com.ugame.meta.player.PlayerRepository;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Простейший матчмейкинг «первые N из очереди». Состояние целиком в Redis, поэтому
 * мета-сервис можно перезапускать без потери очереди.
 *
 * <p>Поток: POST /queue → игрок в ugame:mm:queue → планировщик набирает N игроков, выбирает
 * игровой сервер, создаёт одноразовые билеты → клиент видит в GET /status адрес и билет →
 * подключается к игровому серверу и отправляет билет в JoinRequest.
 *
 * <p>Для нескольких экземпляров мета-сервиса набор матча нужно сделать атомарным
 * (Lua-скрипт или распределённая блокировка) — здесь предполагается один экземпляр.
 */
@Service
public class MatchmakingService {
    private static final Logger log = LoggerFactory.getLogger(MatchmakingService.class);
    private static final Duration STATUS_TTL = Duration.ofMinutes(5);

    public enum State { IDLE, QUEUED, MATCHED }

    public record Status(State state, String matchId, String host, Integer port, String ticket, Integer team) {
        static Status idle() {
            return new Status(State.IDLE, null, null, null, null, null);
        }
    }

    private final StringRedisTemplate redis;
    private final PlayerRepository players;
    private final GameServerDirectory servers;
    private final UgameProperties.Matchmaking props;
    private final SecureRandom random = new SecureRandom();

    public MatchmakingService(StringRedisTemplate redis, PlayerRepository players, GameServerDirectory servers,
                              UgameProperties props) {
        this.redis = redis;
        this.players = players;
        this.servers = servers;
        this.props = props.matchmaking();
    }

    public Status enqueue(long playerId) {
        Status current = status(playerId);
        if (current.state() == State.MATCHED) {
            return current; // уже есть матч — пусть подключается
        }
        Long added = redis.opsForSet().add(RedisKeys.QUEUED_SET, Long.toString(playerId));
        if (added != null && added == 1) {
            redis.opsForList().rightPush(RedisKeys.QUEUE, Long.toString(playerId));
            String key = RedisKeys.status(playerId);
            redis.delete(key);
            redis.opsForHash().put(key, "state", State.QUEUED.name());
            redis.expire(key, STATUS_TTL);
        }
        return status(playerId);
    }

    public void cancel(long playerId) {
        String id = Long.toString(playerId);
        Long removed = redis.opsForSet().remove(RedisKeys.QUEUED_SET, id);
        if (removed != null && removed > 0) {
            redis.opsForList().remove(RedisKeys.QUEUE, 0, id);
            redis.delete(RedisKeys.status(playerId));
        }
    }

    public Status status(long playerId) {
        Map<Object, Object> h = redis.opsForHash().entries(RedisKeys.status(playerId));
        if (h.isEmpty()) {
            return Status.idle();
        }
        State state = State.valueOf((String) h.get("state"));
        return new Status(state,
                (String) h.get("matchId"),
                (String) h.get("host"),
                h.containsKey("port") ? Integer.valueOf((String) h.get("port")) : null,
                (String) h.get("ticket"),
                h.containsKey("team") ? Integer.valueOf((String) h.get("team")) : null);
    }

    /** Набор матчей. fixedDelay — следующий запуск только после завершения предыдущего. */
    @Scheduled(fixedDelayString = "${ugame.matchmaking.poll-interval:500ms}")
    public void formMatches() {
        int size = props.playersPerMatch();
        while (true) {
            Long queued = redis.opsForList().size(RedisKeys.QUEUE);
            if (queued == null || queued < size) {
                return;
            }
            List<String> popped = redis.opsForList().leftPop(RedisKeys.QUEUE, size);
            if (popped == null || popped.isEmpty()) {
                return;
            }
            // отсеиваем тех, кто успел отменить поиск
            List<Long> group = new ArrayList<>();
            for (String id : popped) {
                Long removed = redis.opsForSet().remove(RedisKeys.QUEUED_SET, id);
                if (removed != null && removed > 0) {
                    group.add(Long.parseLong(id));
                }
            }
            if (group.size() < size) {
                // не хватило — возвращаем оставшихся в начало очереди в исходном порядке
                for (int i = group.size() - 1; i >= 0; i--) {
                    String id = group.get(i).toString();
                    redis.opsForSet().add(RedisKeys.QUEUED_SET, id);
                    redis.opsForList().leftPush(RedisKeys.QUEUE, id);
                }
                return;
            }
            createMatch(group);
        }
    }

    private void createMatch(List<Long> playerIds) {
        String matchId = UUID.randomUUID().toString();
        GameServerDirectory.GameServerAddress server = servers.pick();
        Map<Long, Player> byId = players.findAllById(playerIds).stream()
                .collect(Collectors.toMap(Player::getId, Function.identity()));

        for (int i = 0; i < playerIds.size(); i++) {
            long playerId = playerIds.get(i);
            Player player = byId.get(playerId);
            String username = player != null ? player.getUsername() : "player" + playerId;
            int team = i % 2;

            String ticket = newTicket();
            // формат разбирает game-server: MatchTicket.parse
            String value = playerId + ";" + team + ";" + playerIds.size() + ";" + matchId + ";" + username;
            redis.opsForValue().set(RedisKeys.ticket(ticket), value, props.ticketTtl());

            Map<String, String> status = new HashMap<>();
            status.put("state", State.MATCHED.name());
            status.put("matchId", matchId);
            status.put("host", server.host());
            status.put("port", Integer.toString(server.port()));
            status.put("ticket", ticket);
            status.put("team", Integer.toString(team));
            String key = RedisKeys.status(playerId);
            redis.delete(key);
            redis.opsForHash().putAll(key, status);
            redis.expire(key, props.ticketTtl());
        }
        log.info("Матч {} собран: игроки {} → {}:{}", matchId, playerIds, server.host(), server.port());
    }

    private String newTicket() {
        byte[] raw = new byte[24];
        random.nextBytes(raw);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
    }
}
