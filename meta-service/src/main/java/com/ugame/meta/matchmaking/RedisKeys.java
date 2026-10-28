package com.ugame.meta.matchmaking;

/**
 * Ключи Redis. Те, что общие с игровым сервером, должны совпадать с
 * com.ugame.game.redis.RedisKeys в модуле game-server.
 */
public final class RedisKeys {
    private RedisKeys() {}

    // --- общие с game-server ---
    public static String ticket(String ticket) {
        return "ugame:ticket:" + ticket;
    }

    public static final String GAME_SERVER_PATTERN = "ugame:gs:*";
    public static final String MATCH_RESULTS_STREAM = "ugame:match-results";

    // --- только мета-сервис ---
    /** Очередь матчмейкинга (список id игроков в порядке входа). */
    public static final String QUEUE = "ugame:mm:queue";
    /** Множество игроков в очереди — защита от двойной постановки. */
    public static final String QUEUED_SET = "ugame:mm:queued";

    /** Состояние поиска игрока (hash). */
    public static String status(long playerId) {
        return "ugame:mm:status:" + playerId;
    }
}
