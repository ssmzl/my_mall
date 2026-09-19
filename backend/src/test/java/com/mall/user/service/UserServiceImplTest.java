package com.mall.user.service;

import com.mall.common.BizException;
import com.mall.security.AuthInterceptor;
import com.mall.security.JwtUtils;
import com.mall.user.dto.LoginDTO;
import com.mall.user.dto.LoginVO;
import com.mall.user.dto.RegisterDTO;
import com.mall.user.entity.UmsUser;
import com.mall.user.mapper.UserMapper;
import com.mall.user.service.impl.UserServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 用户服务单元测试：注册（BCrypt、唯一校验）、登录（JWT + Redis 会话）、登出。
 */
@ExtendWith(MockitoExtension.class)
class UserServiceImplTest {

    @Mock
    private UserMapper userMapper;

    @Mock
    private StringRedisTemplate stringRedisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();
    private final JwtUtils jwtUtils = new JwtUtils("my-mall-dev-jwt-secret-key-0123456789abcdef", 120);

    private UserServiceImpl userService;

    @BeforeEach
    void setUp() {
        userService = new UserServiceImpl(userMapper, passwordEncoder, jwtUtils, stringRedisTemplate);
    }

    private RegisterDTO buildRegisterDTO() {
        RegisterDTO dto = new RegisterDTO();
        dto.setUsername("newuser");
        dto.setPassword("123456");
        return dto;
    }

    @Test
    void registerShouldEncodePasswordAndInsert() {
        when(userMapper.selectCount(any())).thenReturn(0L);

        userService.register(buildRegisterDTO());

        ArgumentCaptor<UmsUser> captor = ArgumentCaptor.forClass(UmsUser.class);
        verify(userMapper).insert(captor.capture());
        UmsUser inserted = captor.getValue();
        // 密码必须 BCrypt 加密存储，不能是明文
        assertThat(inserted.getPassword()).isNotEqualTo("123456").startsWith("$2");
        assertThat(passwordEncoder.matches("123456", inserted.getPassword())).isTrue();
        // 未传昵称时默认使用用户名，角色为普通用户、状态启用
        assertThat(inserted.getNickname()).isEqualTo("newuser");
        assertThat(inserted.getRole()).isZero();
        assertThat(inserted.getStatus()).isEqualTo(1);
    }

    @Test
    void registerDuplicateUsernameShouldThrow() {
        when(userMapper.selectCount(any())).thenReturn(1L);

        assertThatThrownBy(() -> userService.register(buildRegisterDTO()))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("用户名已存在");
    }

    @Test
    void loginShouldIssueTokenAndWriteRedisSession() {
        UmsUser user = new UmsUser();
        user.setId(1001L);
        user.setUsername("user");
        user.setPassword(passwordEncoder.encode("123456"));
        user.setRole(0);
        user.setStatus(1);
        when(userMapper.selectOne(any())).thenReturn(user);
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);

        LoginDTO dto = new LoginDTO();
        dto.setUsername("user");
        dto.setPassword("123456");
        LoginVO vo = userService.login(dto);

        assertThat(vo.getUserId()).isEqualTo(1001L);
        assertThat(vo.getToken()).isNotBlank();
        // JWT 可正常解析且身份一致
        assertThat(jwtUtils.parseToken(vo.getToken()).get(JwtUtils.CLAIM_USER_ID, Long.class)).isEqualTo(1001L);
        // Redis 会话 key / value / TTL 与约定一致（TTL = JWT 过期时间）
        verify(valueOperations).set(
                eq(AuthInterceptor.TOKEN_KEY_PREFIX + vo.getToken()),
                eq("1001"),
                any(Duration.class));
    }

    @Test
    void loginWrongPasswordShouldThrow() {
        UmsUser user = new UmsUser();
        user.setId(1L);
        user.setUsername("user");
        user.setPassword(passwordEncoder.encode("123456"));
        user.setStatus(1);
        when(userMapper.selectOne(any())).thenReturn(user);

        LoginDTO dto = new LoginDTO();
        dto.setUsername("user");
        dto.setPassword("wrong-password");
        assertThatThrownBy(() -> userService.login(dto))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("用户名或密码错误");
    }

    @Test
    void loginNonexistentUserShouldThrowSameMessage() {
        when(userMapper.selectOne(any())).thenReturn(null);

        LoginDTO dto = new LoginDTO();
        dto.setUsername("ghost");
        dto.setPassword("123456");
        // 不区分「用户不存在」与「密码错误」，防止用户名枚举
        assertThatThrownBy(() -> userService.login(dto))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("用户名或密码错误");
    }

    @Test
    void loginDisabledUserShouldThrow() {
        UmsUser user = new UmsUser();
        user.setId(1L);
        user.setUsername("user");
        user.setPassword(passwordEncoder.encode("123456"));
        user.setStatus(0);
        when(userMapper.selectOne(any())).thenReturn(user);

        LoginDTO dto = new LoginDTO();
        dto.setUsername("user");
        dto.setPassword("123456");
        assertThatThrownBy(() -> userService.login(dto))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("禁用");
    }

    @Test
    void logoutShouldDeleteRedisSession() {
        userService.logout("some-token");

        verify(stringRedisTemplate).delete(AuthInterceptor.TOKEN_KEY_PREFIX + "some-token");
    }
}
