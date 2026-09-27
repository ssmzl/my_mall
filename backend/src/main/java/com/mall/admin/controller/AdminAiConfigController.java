package com.mall.admin.controller;

import com.mall.admin.dto.AdminAiConfigUpdateDTO;
import com.mall.admin.dto.AdminAiConfigVO;
import com.mall.admin.service.AdminAiConfigService;
import com.mall.common.Result;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 管理端 AI 配置接口（/admin/** 由登录拦截器要求管理员角色）。
 */
@Tag(name = "管理端-AI配置", description = "AI 客服配置查看（api-key 脱敏）与修改（保存即时生效）")
@RestController
@RequestMapping("/admin/ai")
@RequiredArgsConstructor
public class AdminAiConfigController {

    private final AdminAiConfigService adminAiConfigService;

    @Operation(summary = "查询 AI 配置", description = "api-key 脱敏：仅返回 hasApiKey 标志，不返回明文")
    @GetMapping("/config")
    public Result<AdminAiConfigVO> get() {
        return Result.ok(adminAiConfigService.get());
    }

    @Operation(summary = "保存 AI 配置", description = "api-key 留空表示保留原值；保存后原地更新配置单例，立即生效无需重启")
    @PutMapping("/config")
    public Result<Void> update(@Valid @RequestBody AdminAiConfigUpdateDTO dto) {
        adminAiConfigService.update(dto);
        return Result.ok(null);
    }
}
