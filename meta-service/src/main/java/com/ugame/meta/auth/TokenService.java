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
import java.util.Optional;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;

/**
 * Access-токен — короткоживущий JWT (HS256), проверяется без обращения к БД.
 * Refresh-токен — случайная строка; в Redis хранится только её SHA-256 → id игрока.
 * При обновлении старый refresh-токен удаляется (ротация), поэтому украденный токен
 * работает максимум до первого обновления настоящим владельцем.
 */
@Service
public class TokenService {
    private static final String REFRESH_PREFIX = "ugame:refresh:";

    private final JwtEncoder jwtEncoder;
    private final StringRedisTemplate redis;
    private final UgameProperties.Jwt props;
    private final SecureRandom random = new SecureRandom();

    public TokenService(JwtEncoder jwtEncoder, StringRedisTemplate redis, UgameProperties props) {
        this.jwtEncoder = jwtEncoder;
        this.redis = redis;
        this.props = props.jwt();
    }

    public AuthDtos.TokenResponse issue(Player player) {
        Instant now = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(props.issuer())
                .subject(player.getId().toString())
                .issuedAt(now)
                .expiresAt(now.plus(props.accessTtl()))
                .claim("username", player.getUsername())
                .build();
        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
        String access = jwtEncoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();

        byte[] raw = new byte[32];
        random.nextBytes(raw);
        String refresh = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
        redis.opsForValue().set(REFRESH_PREFIX + sha256(refresh), player.getId().toString(), props.refreshTtl());

        return new AuthDtos.TokenResponse(access, refresh, props.accessTtl().toSeconds(), player.getId(),
                player.getUsername());
    }

    /** Погасить refresh-токен. Возвращает id игрока, если токен был действителен. */
    public Optional<Long> consumeRefreshToken(String refreshToken) {
        String playerId = redis.opsForValue().getAndDelete(REFRESH_PREFIX + sha256(refreshToken));
        return Optional.ofNullable(playerId).map(Long::parseLong);
    }

    public void revoke(String refreshToken) {
        redis.delete(REFRESH_PREFIX + sha256(refreshToken));
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
