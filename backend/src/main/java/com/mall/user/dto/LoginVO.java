package com.mall.user.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * 登录响应：访问令牌 + 用户基础信息。
 */
@Data
@AllArgsConstructor
public class LoginVO {

    private String token;

    private Long userId;

    private String username;

    private String nickname;

    /** 角色：0普通用户 1管理员 */
    private Integer role;
}
