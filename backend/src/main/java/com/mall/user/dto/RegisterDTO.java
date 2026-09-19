package com.mall.user.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 注册请求参数。
 */
@Data
public class RegisterDTO {

    /** 用户名：4-32 位字母/数字/下划线 */
    @NotBlank(message = "用户名不能为空")
    @Pattern(regexp = "^[a-zA-Z0-9_]{4,32}$", message = "用户名须为4-32位字母、数字或下划线")
    private String username;

    /** 密码：6-32 位 */
    @NotBlank(message = "密码不能为空")
    @Size(min = 6, max = 32, message = "密码长度须为6-32位")
    private String password;

    @Size(max = 64, message = "昵称最长64位")
    private String nickname;

    @Pattern(regexp = "^1[3-9]\\d{9}$", message = "手机号格式不正确")
    private String phone;
}
