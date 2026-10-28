package com.ugame.game.redis;

/** Данные билета, выданного мета-сервисом при формировании матча. */
public record MatchTicket(long playerId, int team, int matchSize, String matchId, String username) {

    /** Формат: {@code playerId;team;matchSize;matchId;username} (username — последним, может быть любым). */
    public static MatchTicket parse(String value) {
        String[] parts = value.split(";", 5);
        if (parts.length != 5) {
            throw new IllegalArgumentException("bad ticket value");
        }
        return new MatchTicket(
                Long.parseLong(parts[0]),
                Integer.parseInt(parts[1]),
                Integer.parseInt(parts[2]),
                parts[3],
                parts[4]);
    }
}
