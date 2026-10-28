package com.ugame.game;

import com.ugame.game.net.KcpServer;
import com.ugame.game.redis.RedisGateway;
import com.ugame.game.room.RoomManager;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Точка входа игрового сервера.
 *
 * <pre>
 * Unity (kcp2k) ──UDP──▶ KcpServer (Netty, 1 поток) ──▶ GameNetHandler ──▶ Room (пул потоков, 30 Гц)
 *                                         ▲                    │
 *                                         └──── снапшоты ◀──────┘
 * Redis: билеты на вход (от мета-сервиса), heartbeat сервера, результаты матчей.
 * </pre>
 */
public final class GameServerMain {
    private static final Logger log = LoggerFactory.getLogger(GameServerMain.class);

    public static void main(String[] args) throws Exception {
        GameServerConfig config = GameServerConfig.load();

        RedisGateway redis = new RedisGateway(config.redisUri());
        RoomManager rooms = new RoomManager(config.rooms(), redis::publishMatchResult);
        KcpServer server = new KcpServer(config.kcp(), new GameNetHandler(rooms, redis));
        server.start();

        ScheduledExecutorService heartbeat = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "heartbeat");
            t.setDaemon(true);
            return t;
        });
        heartbeat.scheduleAtFixedRate(
                () -> redis.heartbeat(config.serverId(), config.publicHost(), config.kcp().port(),
                        rooms.roomCount(), config.heartbeatSec() * 3L),
                0, config.heartbeatSec(), TimeUnit.SECONDS);

        log.info("Игровой сервер {} запущен: {}:{}, тик {} Гц",
                config.serverId(), config.publicHost(), config.kcp().port(), config.rooms().tickRate());

        CountDownLatch stopped = new CountDownLatch(1);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            log.info("Остановка...");
            heartbeat.shutdownNow();
            redis.removeServer(config.serverId());
            server.close();
            rooms.close();
            redis.close();
            stopped.countDown();
        }, "shutdown"));
        stopped.await();
    }
}
