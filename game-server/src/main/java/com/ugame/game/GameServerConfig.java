package com.ugame.game;

import com.ugame.game.net.KcpConfig;
import com.ugame.game.room.RoomSettings;
import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;
import java.util.UUID;

/**
 * Настройки игрового сервера. Порядок приоритета (от низшего к высшему):
 * {@code game-server.properties} в ресурсах → переменные окружения → -D параметры JVM.
 *
 * <p>Ключ {@code udp.port} можно переопределить переменной {@code UGAME_UDP_PORT}
 * или параметром {@code -Dugame.udp.port=7778}.
 */
public record GameServerConfig(
        String serverId,
        String publicHost,
        KcpConfig kcp,
        RoomSettings rooms,
        String redisUri,
        int heartbeatSec) {

    public static GameServerConfig load() {
        Properties p = new Properties();
        try (InputStream in = GameServerConfig.class.getResourceAsStream("/game-server.properties")) {
            if (in != null) {
                p.load(in);
            }
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read game-server.properties", e);
        }

        int port = intValue(p, "udp.port");
        KcpConfig d = KcpConfig.defaults(port);
        KcpConfig kcp = new KcpConfig(
                value(p, "udp.host"),
                port,
                intValue(p, "kcp.mtu", d.mtu()),
                boolValue(p, "kcp.no-delay", d.noDelay()),
                intValue(p, "kcp.interval-ms", d.intervalMs()),
                intValue(p, "kcp.fast-resend", d.fastResend()),
                boolValue(p, "kcp.congestion-window", d.congestionWindow()),
                intValue(p, "kcp.send-window", d.sendWindow()),
                intValue(p, "kcp.receive-window", d.receiveWindow()),
                intValue(p, "kcp.timeout-ms", d.timeoutMs()),
                intValue(p, "kcp.max-connections", d.maxConnections()),
                intValue(p, "kcp.socket-buffer-bytes", d.socketBufferBytes()));

        int threads = intValue(p, "room.threads", 0);
        RoomSettings rooms = new RoomSettings(
                intValue(p, "room.tick-rate"),
                intValue(p, "room.snapshot-every"),
                intValue(p, "match.duration-sec"),
                intValue(p, "match.join-timeout-sec"),
                threads > 0 ? threads : Runtime.getRuntime().availableProcessors());

        String serverId = value(p, "server.id");
        if (serverId == null || serverId.isBlank()) {
            serverId = UUID.randomUUID().toString();
        }

        return new GameServerConfig(
                serverId,
                value(p, "server.public-host"),
                kcp,
                rooms,
                value(p, "redis.uri"),
                intValue(p, "server.heartbeat-sec", 2));
    }

    private static String value(Properties p, String key) {
        String sys = System.getProperty("ugame." + key);
        if (sys != null) {
            return sys;
        }
        String env = System.getenv("UGAME_" + key.toUpperCase().replace('.', '_').replace('-', '_'));
        if (env != null) {
            return env;
        }
        return p.getProperty(key);
    }

    private static int intValue(Properties p, String key) {
        String v = value(p, key);
        if (v == null) {
            throw new IllegalStateException("Parameter not specified: " + key);
        }
        return Integer.parseInt(v.trim());
    }

    private static int intValue(Properties p, String key, int def) {
        String v = value(p, key);
        return v == null ? def : Integer.parseInt(v.trim());
    }

    private static boolean boolValue(Properties p, String key, boolean def) {
        String v = value(p, key);
        return v == null ? def : Boolean.parseBoolean(v.trim());
    }
}
