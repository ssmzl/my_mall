package com.mall.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;

/**
 * JWT 工具：签发与解析访问令牌。claims 包含 userId、username、role。
 */
@Component
public class JwtUtils {

    public static final String CLAIM_USER_ID = "userId";
    public static final String CLAIM_USERNAME = "username";
    public static final String CLAIM_ROLE = "role";

    private final SecretKey secretKey;
    private final long expireMillis;

    public JwtUtils(@Value("${mall.jwt.secret}") String secret,
                    @Value("${mall.jwt.expire-minutes:120}") long expireMinutes) {
        this.secretKey = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.expireMillis = expireMinutes * 60_000L;
    }

    public String createToken(Long userId, String username, Integer role) {
        Date now = new Date();
        return Jwts.builder()
                .claim(CLAIM_USER_ID, userId)
                .claim(CLAIM_USERNAME, username)
                .claim(CLAIM_ROLE, role)
                .issuedAt(now)
                .expiration(new Date(now.getTime() + expireMillis))
                .signWith(secretKey)
                .compact();
    }

    /** token 有效期（毫秒），登录时 Redis 会话 TTL 应与此保持一致 */
    public long getExpireMillis() {
        return expireMillis;
    }

    /**
     * 解析并校验 token。签名非法、被篡改或已过期时抛出 {@link io.jsonwebtoken.JwtException}。
     */
    public Claims parseToken(String token) {
        return Jwts.parser()
                .verifyWith(secretKey)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }
}
