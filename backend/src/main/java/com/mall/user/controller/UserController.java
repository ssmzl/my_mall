package com.mall.user.controller;

import com.mall.common.Result;
import com.mall.user.dto.LoginDTO;
import com.mall.user.dto.LoginVO;
import com.mall.user.dto.RegisterDTO;
import com.mall.user.service.UserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 用户接口：注册 / 登录 / 登出。
 */
@Tag(name = "用户", description = "注册、登录、登出")
@RestController
@RequestMapping("/user")
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;

    @Operation(summary = "注册")
    @PostMapping("/register")
    public Result<Void> register(@Valid @RequestBody RegisterDTO dto) {
        userService.register(dto);
        return Result.ok();
    }

    @Operation(summary = "登录")
    @PostMapping("/login")
    public Result<LoginVO> login(@Valid @RequestBody LoginDTO dto) {
        return Result.ok(userService.login(dto));
    }

    @Operation(summary = "登出", description = "使当前 token 失效")
    @PostMapping("/logout")
    public Result<Void> logout(@org.springframework.web.bind.annotation.RequestHeader(HttpHeaders.AUTHORIZATION) String authorization) {
        userService.logout(authorization.substring(7));
        return Result.ok();
    }
}
