package com.mall.common;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 健康检查，用于验证应用启动（路径 /api/health，已在拦截器白名单中放行）。
 */
@RestController
public class HealthController {

    @GetMapping("/health")
    public Result<String> health() {
        return Result.ok("UP");
    }
}
