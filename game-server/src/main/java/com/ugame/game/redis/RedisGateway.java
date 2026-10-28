package com.ugame.game.redis;

import io.lettuce.core.RedisClient;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.async.RedisAsyncCommands;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Всё общение игрового сервера с Redis. Только асинхронные вызовы: потоки event loop и комнат
 * никогда не блокируются на сети.
 */
public final class RedisGateway implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(RedisGateway.class);

    private final RedisClient client;
    private final StatefulRedisConnection<String, String> connection;
    private final RedisAsyncCommands<String, String> async;

    public RedisGateway(String redisUri) {
        this.client = RedisClient.create(redisUri);
        this.connection = client.connect();
        this.async = connection.async();
        log.info("Подключились к Redis {}", redisUri);
    }

    /**
     * Погасить билет: GETDEL атомарно читает и удаляет ключ, поэтому один билет нельзя
     * использовать дважды. Результат null — билет не найден или просрочен.
     */
    public CompletionStage<MatchTicket> redeemTicket(String ticket) {
        if (ticket == null || ticket.isBlank() || ticket.length() > 128) {
            return CompletableFuture.completedFuture(null);
        }
        return async.getdel(RedisKeys.ticket(ticket))
                .thenApply(value -> value == null ? null : MatchTicket.parse(value));
    }

    /** Сердцебиение: мета-сервис выбирает живой сервер по этим ключам. */
    public void heartbeat(String serverId, String publicHost, int port, int rooms, long ttlSeconds) {
        async.setex(RedisKeys.gameServer(serverId), ttlSeconds, publicHost + ":" + port + ":" + rooms)
                .exceptionally(e -> {
                    log.warn("Не удалось отправить heartbeat: {}", e.toString());
                    return null;
                });
    }

    /** Публикация результата матча в Redis Stream. */
    public void publishMatchResult(String matchId, int winnerTeam, String results) {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("matchId", matchId);
        fields.put("winnerTeam", Integer.toString(winnerTeam));
        fields.put("results", results); // playerId:team:kills:deaths,...
        async.xadd(RedisKeys.MATCH_RESULTS_STREAM, fields)
                .exceptionally(e -> {
                    log.error("Не удалось записать результат матча {}: {}", matchId, e.toString());
                    return null;
                });
    }

    public void removeServer(String serverId) {
        try {
            connection.sync().del(RedisKeys.gameServer(serverId));
        } catch (RuntimeException e) {
            log.warn("Не удалось снять регистрацию сервера: {}", e.toString());
        }
    }

    @Override
    public void close() {
        connection.close();
        client.shutdown();
    }
}
