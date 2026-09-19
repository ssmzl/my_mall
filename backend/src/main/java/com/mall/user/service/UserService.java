package com.mall.user.service;

import com.mall.user.dto.LoginDTO;
import com.mall.user.dto.LoginVO;
import com.mall.user.dto.RegisterDTO;

/**
 * 用户服务：注册、登录、登出。
 */
public interface UserService {

    /**
     * 注册：BCrypt 加密存储密码，用户名唯一。
     */
    void register(RegisterDTO dto);

    /**
     * 登录：校验用户名密码，签发 JWT 并写入 Redis 会话。
     */
    LoginVO login(LoginDTO dto);

    /**
     * 登出：删除 Redis 会话，使 token 失效。
     *
     * @param token 已去除 Bearer 前缀的访问令牌
     */
    void logout(String token);
}
