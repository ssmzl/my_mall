package com.mall.security;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 当前登录用户持有者。由 AuthInterceptor 在请求进入时写入、请求结束后清除。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class LoginUser {

    private static final ThreadLocal<LoginUser> HOLDER = new ThreadLocal<>();

    private Long userId;
    private String username;
    /** 角色：0-普通用户，1-管理员 */
    private Integer role;

    public static void set(LoginUser loginUser) {
        HOLDER.set(loginUser);
    }

    public static LoginUser get() {
        return HOLDER.get();
    }

    public static Long getCurrentUserId() {
        LoginUser loginUser = HOLDER.get();
        return loginUser == null ? null : loginUser.getUserId();
    }

    public static void remove() {
        HOLDER.remove();
    }
}
