package com.ugame.meta.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "ugame")
public record UgameProperties(Db db, Jwt jwt, Matchmaking matchmaking) {

    /** Параметры PostgreSQL. Из них собираются URL для R2DBC (application.yml) и для Flyway (JDBC). */
    public record Db(String host, int port, String name, String username, String password) {
        public String jdbcUrl() {
            return "jdbc:postgresql://" + host + ":" + port + "/" + name;
        }
    }

    /**
     * @param secret     ключ HMAC-SHA256 в Base64, не короче 32 байт (в проде — из переменной окружения)
     * @param issuer     значение claim "iss"
     * @param accessTtl  срок жизни access-токена
     * @param refreshTtl срок жизни refresh-токена
     */
    public record Jwt(String secret, String issuer, Duration accessTtl, Duration refreshTtl) {}

    /**
     * @param playersPerMatch    игроков в матче (6 для 3×3; для отладки удобно 1–2)
     * @param ticketTtl          сколько живёт билет на вход в игровой сервер
     * @param pollInterval       период набора матчей и чтения результатов
     * @param fallbackServerHost адрес игрового сервера, если ни один не прислал heartbeat
     * @param fallbackServerPort порт этого сервера
     */
    public record Matchmaking(int playersPerMatch, Duration ticketTtl, Duration pollInterval,
                              String fallbackServerHost, int fallbackServerPort) {}
}
