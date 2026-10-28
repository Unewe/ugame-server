package com.ugame.game.redis;

/**
 * Ключи Redis, общие для игрового сервера и мета-сервиса.
 * Те же строки объявлены в meta-service: com.ugame.meta.matchmaking.RedisKeys.
 */
public final class RedisKeys {
    private RedisKeys() {}

    /**
     * Одноразовый билет на вход в матч. Значение (строка, поля через ';'):
     * {@code playerId;team;matchSize;matchId;username}. Живёт 60 секунд, забирается GETDEL.
     */
    public static String ticket(String ticket) {
        return "ugame:ticket:" + ticket;
    }

    /** Сердцебиение игрового сервера: значение {@code host:port:rooms}, TTL несколько секунд. */
    public static String gameServer(String serverId) {
        return "ugame:gs:" + serverId;
    }

    public static final String GAME_SERVER_PATTERN = "ugame:gs:*";

    /** Redis Stream с результатами матчей; читает мета-сервис. */
    public static final String MATCH_RESULTS_STREAM = "ugame:match-results";
}
