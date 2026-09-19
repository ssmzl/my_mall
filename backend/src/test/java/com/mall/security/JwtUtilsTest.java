package com.mall.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import org.junit.jupiter.api.Test;

import java.util.Date;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JwtUtilsTest {

    private static final String SECRET = "my-mall-dev-jwt-secret-key-0123456789abcdef";

    private final JwtUtils jwtUtils = new JwtUtils(SECRET, 120);

    @Test
    void createAndParseTokenShouldRoundTrip() {
        String token = jwtUtils.createToken(1001L, "tester", 1);

        Claims claims = jwtUtils.parseToken(token);

        assertThat(claims.get(JwtUtils.CLAIM_USER_ID, Long.class)).isEqualTo(1001L);
        assertThat(claims.get(JwtUtils.CLAIM_USERNAME, String.class)).isEqualTo("tester");
        assertThat(claims.get(JwtUtils.CLAIM_ROLE, Integer.class)).isEqualTo(1);
        assertThat(claims.getExpiration()).isAfter(new Date());
        // 2 小时过期窗口
        long ttlMillis = claims.getExpiration().getTime() - claims.getIssuedAt().getTime();
        assertThat(ttlMillis).isEqualTo(120 * 60_000L);
    }

    @Test
    void parseTokenSignedByOtherSecretShouldFail() {
        String token = jwtUtils.createToken(1L, "attacker", 0);
        JwtUtils other = new JwtUtils("another-secret-key-that-is-long-enough-123456", 120);

        assertThatThrownBy(() -> other.parseToken(token)).isInstanceOf(JwtException.class);
    }

    @Test
    void parseTamperedTokenShouldFail() {
        String token = jwtUtils.createToken(1L, "tester", 0);
        String tampered = token.substring(0, token.length() - 2) + "xx";

        assertThatThrownBy(() -> jwtUtils.parseToken(tampered)).isInstanceOf(JwtException.class);
    }
}
