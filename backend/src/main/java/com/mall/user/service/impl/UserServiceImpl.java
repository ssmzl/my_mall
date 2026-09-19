package com.mall.user.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.mall.common.BizException;
import com.mall.security.AuthInterceptor;
import com.mall.security.JwtUtils;
import com.mall.user.dto.LoginDTO;
import com.mall.user.dto.LoginVO;
import com.mall.user.dto.RegisterDTO;
import com.mall.user.entity.UmsUser;
import com.mall.user.mapper.UserMapper;
import com.mall.user.service.UserService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

import java.time.Duration;

/**
 * 用户服务实现。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UserServiceImpl implements UserService {

    private final UserMapper userMapper;
    private final BCryptPasswordEncoder passwordEncoder;
    private final JwtUtils jwtUtils;
    private final StringRedisTemplate stringRedisTemplate;

    @Override
    public void register(RegisterDTO dto) {
        Long count = userMapper.selectCount(new LambdaQueryWrapper<UmsUser>()
                .eq(UmsUser::getUsername, dto.getUsername()));
        if (count != null && count > 0) {
            throw new BizException("用户名已存在");
        }

        UmsUser user = new UmsUser();
        user.setUsername(dto.getUsername());
        user.setPassword(passwordEncoder.encode(dto.getPassword()));
        user.setNickname(dto.getNickname() == null || dto.getNickname().isBlank()
                ? dto.getUsername() : dto.getNickname());
        user.setPhone(dto.getPhone());
        user.setRole(0);
        user.setStatus(1);
        userMapper.insert(user);
        log.info("用户注册成功: userId={}, username={}", user.getId(), user.getUsername());
    }

    @Override
    public LoginVO login(LoginDTO dto) {
        UmsUser user = userMapper.selectOne(new LambdaQueryWrapper<UmsUser>()
                .eq(UmsUser::getUsername, dto.getUsername()));
        if (user == null || !passwordEncoder.matches(dto.getPassword(), user.getPassword())) {
            // 不区分「用户不存在」与「密码错误」，避免用户名枚举
            throw new BizException("用户名或密码错误");
        }
        if (user.getStatus() == null || user.getStatus() != 1) {
            throw new BizException("账号已被禁用，请联系管理员");
        }

        String token = jwtUtils.createToken(user.getId(), user.getUsername(), user.getRole());
        // Redis 会话：登录时写入、登出删除、拦截器校验，TTL 与 JWT 过期时间一致
        stringRedisTemplate.opsForValue().set(
                AuthInterceptor.TOKEN_KEY_PREFIX + token,
                String.valueOf(user.getId()),
                Duration.ofMillis(jwtUtils.getExpireMillis()));
        log.info("用户登录成功: userId={}, username={}", user.getId(), user.getUsername());
        return new LoginVO(token, user.getId(), user.getUsername(), user.getNickname(), user.getRole());
    }

    @Override
    public void logout(String token) {
        Boolean deleted = stringRedisTemplate.delete(AuthInterceptor.TOKEN_KEY_PREFIX + token);
        log.info("用户登出: tokenDeleted={}", deleted);
    }
}
