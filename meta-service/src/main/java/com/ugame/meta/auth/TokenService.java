package com.ugame.meta.auth;

import com.ugame.meta.config.UgameProperties;
import com.ugame.meta.player.Player;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

/**
 * Access-токен — короткоживущий JWT (HS256), проверяется без обращения к БД.
 * Refresh-токен — случайная строка; в Redis хранится только её SHA-256 → id игрока.
 * При обновлении старый refresh-токен удаляется (ротация), поэтому украденный токен
 * работает максимум до первого обновления настоящим владельцем.
 */
@Service
@RequiredArgsConstructor
public class TokenService {
    private static final String REFRESH_PREFIX = "ugame:refresh:";
    private static final SecureRandom RANDOM = new SecureRandom();

    private final JwtEncoder jwtEncoder;
    private final ReactiveStringRedisTemplate redis;
    private final UgameProperties props;

    public Mono<AuthDtos.TokenResponse> issue(Player player) {
        UgameProperties.Jwt jwt = props.jwt();
        Instant now = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(jwt.issuer())
                .subject(player.getId().toString())
                .issuedAt(now)
                .expiresAt(now.plus(jwt.accessTtl()))
                .claim("username", player.getUsername())
                .build();
        String access = jwtEncoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims))
                .getTokenValue();
        String refresh = randomToken();

        return redis.opsForValue()
                .set(REFRESH_PREFIX + sha256(refresh), player.getId().toString(), jwt.refreshTtl())
                .thenReturn(new AuthDtos.TokenResponse(access, refresh, jwt.accessTtl().toSeconds(),
                        player.getId(), player.getUsername()));
    }

    /** Погасить refresh-токен (GETDEL). Пустой Mono — токен недействителен. */
    public Mono<Long> consumeRefreshToken(String refreshToken) {
        return redis.opsForValue().getAndDelete(REFRESH_PREFIX + sha256(refreshToken)).map(Long::parseLong);
    }

    public Mono<Void> revoke(String refreshToken) {
        return redis.delete(REFRESH_PREFIX + sha256(refreshToken)).then();
    }

    private static String randomToken() {
        byte[] raw = new byte[32];
        RANDOM.nextBytes(raw);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
    }

    private static String sha256(String value) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
