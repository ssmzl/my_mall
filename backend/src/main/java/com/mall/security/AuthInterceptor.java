package com.mall.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mall.common.Result;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.servlet.HandlerInterceptor;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * 登录校验拦截器：解析 Authorization: Bearer {token}，校验 JWT 签名/过期，
 * 并核对 Redis 中的会话（key: mall:token:{token}，登录时写入、登出删除）。
 * 管理员接口（/api/admin/**）要求 role == 1。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AuthInterceptor implements HandlerInterceptor {

    /** Redis 会话 key 前缀，与登录逻辑约定一致 */
    public static final String TOKEN_KEY_PREFIX = "mall:token:";
    /** 管理员角色 */
    public static final int ROLE_ADMIN = 1;

    private static final AntPathMatcher PATH_MATCHER = new AntPathMatcher();

    /** 任意请求方法放行的路径（相对 context-path 的路径，不含 /api 前缀） */
    private static final List<String> WHITE_LIST = List.of(
            "/user/login",
            "/user/register",
            "/health",
            "/doc.html",
            "/v3/api-docs/**",
            "/swagger-resources/**",
            "/webjars/**",
            "/favicon.ico",
            "/error"
    );

    /** 仅 GET 请求放行的路径 */
    private static final List<String> WHITE_LIST_GET = List.of(
            "/product/**",
            "/search/**",
            "/seckill/activities"
    );

    private static final String ADMIN_PATH_PATTERN = "/admin/**";

    private final JwtUtils jwtUtils;
    private final StringRedisTemplate stringRedisTemplate;
    private final ObjectMapper objectMapper;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        // 剥离 context-path（/api），统一用相对路径做白名单与管理员路径匹配
        String contextPath = request.getContextPath();
        String path = contextPath.isEmpty() ? request.getRequestURI()
                : request.getRequestURI().substring(contextPath.length());

        if (isWhitelisted(path, request.getMethod())) {
            return true;
        }

        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header == null || !header.startsWith("Bearer ")) {
            log.debug("拦截未登录请求: uri={}, 缺少 Authorization 头", path);
            writeError(response, HttpServletResponse.SC_UNAUTHORIZED, Result.CODE_UNAUTHORIZED, "未登录");
            return false;
        }
        String token = header.substring(7);

        Claims claims;
        try {
            claims = jwtUtils.parseToken(token);
        } catch (ExpiredJwtException e) {
            log.debug("拦截过期令牌: uri={}, msg={}", path, e.getMessage());
            writeError(response, HttpServletResponse.SC_UNAUTHORIZED, Result.CODE_UNAUTHORIZED, "登录已过期，请重新登录");
            return false;
        } catch (JwtException | IllegalArgumentException e) {
            log.warn("拦截无效令牌: uri={}, error={}", path, e.getMessage());
            writeError(response, HttpServletResponse.SC_UNAUTHORIZED, Result.CODE_UNAUTHORIZED, "登录状态无效，请重新登录");
            return false;
        }

        Boolean exists = stringRedisTemplate.hasKey(TOKEN_KEY_PREFIX + token);
        if (!Boolean.TRUE.equals(exists)) {
            log.debug("拦截失效会话: uri={}", path);
            writeError(response, HttpServletResponse.SC_UNAUTHORIZED, Result.CODE_UNAUTHORIZED, "会话不存在或已失效，请重新登录");
            return false;
        }

        Long userId = claims.get(JwtUtils.CLAIM_USER_ID, Long.class);
        String username = claims.get(JwtUtils.CLAIM_USERNAME, String.class);
        Integer role = claims.get(JwtUtils.CLAIM_ROLE, Integer.class);

        if (PATH_MATCHER.match(ADMIN_PATH_PATTERN, path) && !Integer.valueOf(ROLE_ADMIN).equals(role)) {
            log.warn("拦截越权访问管理接口: uri={}, userId={}, role={}", path, userId, role);
            writeError(response, HttpServletResponse.SC_FORBIDDEN, Result.CODE_FORBIDDEN, "无权限访问");
            return false;
        }

        LoginUser.set(new LoginUser(userId, username, role));
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {
        LoginUser.remove();
    }

    private boolean isWhitelisted(String uri, String method) {
        for (String pattern : WHITE_LIST) {
            if (PATH_MATCHER.match(pattern, uri)) {
                return true;
            }
        }
        if ("GET".equalsIgnoreCase(method)) {
            for (String pattern : WHITE_LIST_GET) {
                if (PATH_MATCHER.match(pattern, uri)) {
                    return true;
                }
            }
        }
        return false;
    }

    private void writeError(HttpServletResponse response, int httpStatus, int code, String message) throws IOException {
        response.setStatus(httpStatus);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write(objectMapper.writeValueAsString(Result.fail(code, message)));
    }
}
