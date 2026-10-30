package com.ugame.meta.matchmaking;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.ugame.meta.config.UgameProperties;
import com.ugame.meta.player.Player;
import com.ugame.meta.player.PlayerRepository;
import jakarta.annotation.PreDestroy;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.data.redis.core.ReactiveHashOperations;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.stereotype.Service;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Простейший матчмейкинг «первые N из очереди». Состояние целиком в Redis, поэтому
 * мета-сервис можно перезапускать без потери очереди.
 *
 * <p>Поток: POST /queue → игрок в ugame:mm:queue → фоновый цикл набирает N игроков, выбирает
 * игровой сервер, создаёт одноразовые билеты → клиент видит в GET /status адрес и билет →
 * подключается к игровому серверу и отправляет билет в JoinRequest.
 *
 * <p>Для нескольких экземпляров мета-сервиса набор матча нужно сделать атомарным
 * (Lua-скрипт или распределённая блокировка) — здесь предполагается один экземпляр.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MatchmakingService {
    private static final Duration STATUS_TTL = Duration.ofMinutes(5);
    private static final SecureRandom RANDOM = new SecureRandom();

    public enum State { IDLE, QUEUED, MATCHED }

    /** Пустые поля не попадают в JSON: клиенту (Unity JsonUtility) так проще. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Status(State state, String matchId, String host, Integer port, String ticket, Integer team) {
        static final Status IDLE = new Status(State.IDLE, null, null, null, null, null);
    }

    private final ReactiveStringRedisTemplate redis;
    private final PlayerRepository players;
    private final GameServerDirectory servers;
    private final UgameProperties props;

    private Disposable loop;

    // ------------------------------------------------------------------ API

    public Mono<Status> enqueue(long playerId) {
        String id = Long.toString(playerId);
        String key = RedisKeys.status(playerId);
        return status(playerId).flatMap(current -> {
            if (current.state() == State.MATCHED) {
                return Mono.just(current); // матч уже есть — пусть подключается
            }
            return redis.opsForSet().add(RedisKeys.QUEUED_SET, id)
                    .filter(added -> added == 1) // уже в очереди — ничего не делаем
                    .flatMap(added -> redis.opsForList().rightPush(RedisKeys.QUEUE, id)
                            .then(redis.delete(key))
                            .then(hash().put(key, "state", State.QUEUED.name()))
                            .then(redis.expire(key, STATUS_TTL)))
                    .then(status(playerId));
        });
    }

    public Mono<Status> cancel(long playerId) {
        String id = Long.toString(playerId);
        return redis.opsForSet().remove(RedisKeys.QUEUED_SET, id)
                .filter(removed -> removed > 0)
                .flatMap(removed -> redis.opsForList().remove(RedisKeys.QUEUE, 0, id)
                        .then(redis.delete(RedisKeys.status(playerId))))
                .then(status(playerId));
    }

    public Mono<Status> status(long playerId) {
        return hash().entries(RedisKeys.status(playerId))
                .collectMap(Map.Entry::getKey, Map.Entry::getValue)
                .map(h -> h.isEmpty() ? Status.IDLE : new Status(
                        State.valueOf(h.get("state")),
                        h.get("matchId"),
                        h.get("host"),
                        h.containsKey("port") ? Integer.valueOf(h.get("port")) : null,
                        h.get("ticket"),
                        h.containsKey("team") ? Integer.valueOf(h.get("team")) : null));
    }

    // ------------------------------------------------------------------ фоновый набор матчей

    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        loop = Flux.interval(props.matchmaking().pollInterval())
                .onBackpressureDrop() // предыдущий набор ещё идёт — пропускаем тик, а не копим
                .concatMap(tick -> formMatches()
                        .onErrorResume(e -> {
                            log.error("Ошибка набора матчей", e);
                            return Mono.empty();
                        }), 1)
                .subscribe();
    }

    @PreDestroy
    public void stop() {
        if (loop != null) {
            loop.dispose();
        }
    }

    /** Собирает матчи, пока в очереди хватает игроков. */
    Mono<Void> formMatches() {
        return Mono.defer(this::formOneMatch)
                .repeat()
                .takeWhile(Boolean::booleanValue)
                .then();
    }

    /** true — матч собран (можно пробовать ещё один), false — игроков не хватило. */
    private Mono<Boolean> formOneMatch() {
        int size = props.matchmaking().playersPerMatch();
        return redis.opsForList().size(RedisKeys.QUEUE)
                .filter(queued -> queued >= size)
                .flatMap(queued -> redis.opsForList().leftPop(RedisKeys.QUEUE, size)
                        // отсеиваем тех, кто успел отменить поиск
                        .concatMap(id -> redis.opsForSet().remove(RedisKeys.QUEUED_SET, id)
                                .filter(removed -> removed > 0)
                                .map(removed -> Long.parseLong(id)))
                        .collectList())
                .flatMap(group -> group.size() == size
                        ? createMatch(group).thenReturn(true)
                        : returnToQueue(group).thenReturn(false))
                .defaultIfEmpty(false);
    }

    /** Не хватило — возвращаем оставшихся в начало очереди в исходном порядке. */
    private Mono<Void> returnToQueue(List<Long> group) {
        return Flux.fromIterable(group.reversed())
                .map(String::valueOf)
                .concatMap(id -> redis.opsForSet().add(RedisKeys.QUEUED_SET, id)
                        .then(redis.opsForList().leftPush(RedisKeys.QUEUE, id)))
                .then();
    }

    private Mono<Void> createMatch(List<Long> playerIds) {
        String matchId = UUID.randomUUID().toString();
        Duration ttl = props.matchmaking().ticketTtl();

        Mono<Map<Long, String>> names = players.findAllById(playerIds)
                .collectMap(Player::getId, Player::getUsername);

        return Mono.zip(servers.pick(), names).flatMap(t -> {
            GameServerDirectory.GameServerAddress server = t.getT1();
            Map<Long, String> byId = t.getT2();

            return Flux.range(0, playerIds.size()).concatMap(i -> {
                long playerId = playerIds.get(i);
                String username = byId.getOrDefault(playerId, "player" + playerId);
                int team = i % 2;
                String ticket = newTicket();
                // формат разбирает game-server: MatchTicket.parse
                String value = playerId + ";" + team + ";" + playerIds.size() + ";" + matchId + ";" + username;
                String key = RedisKeys.status(playerId);
                Map<String, String> status = Map.of(
                        "state", State.MATCHED.name(),
                        "matchId", matchId,
                        "host", server.host(),
                        "port", Integer.toString(server.port()),
                        "ticket", ticket,
                        "team", Integer.toString(team));

                return redis.opsForValue().set(RedisKeys.ticket(ticket), value, ttl)
                        .then(redis.delete(key))
                        .then(hash().putAll(key, status))
                        .then(redis.expire(key, ttl));
            }).then().doOnSuccess(v -> log.info("Матч {} собран: игроки {} → {}:{}",
                    matchId, playerIds, server.host(), server.port()));
        });
    }

    private ReactiveHashOperations<String, String, String> hash() {
        return redis.opsForHash();
    }

    private static String newTicket() {
        byte[] raw = new byte[24];
        RANDOM.nextBytes(raw);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
    }
}
