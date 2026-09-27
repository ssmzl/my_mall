package com.mall.admin.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.math.BigDecimal;

/**
 * 管理端修改 AI 配置请求。
 * <p>api-key 传 null/空白表示保留原值（数据库已有值或 yml 配置值）。</p>
 */
@Data
public class AdminAiConfigUpdateDTO {

    /** 是否启用 AI 客服 */
    @NotNull(message = "enabled 不能为空")
    private Boolean enabled;

    /** LLM chat completions 端点 URL */
    @NotBlank(message = "base-url 不能为空")
    @Size(max = 512, message = "base-url 长度不能超过 512")
    private String baseUrl;

    /** LLM API Key（留空保留原值） */
    @Size(max = 512, message = "api-key 长度不能超过 512")
    private String apiKey;

    /** 模型名称 */
    @NotBlank(message = "model 不能为空")
    @Size(max = 64, message = "model 长度不能超过 64")
    private String model;

    /** 温度（0-2） */
    @NotNull(message = "temperature 不能为空")
    @DecimalMin(value = "0", message = "temperature 不能小于 0")
    @DecimalMax(value = "2", message = "temperature 不能大于 2")
    private BigDecimal temperature;

    /** 上下文保留轮数 */
    @NotNull(message = "max-rounds 不能为空")
    @Min(value = 1, message = "max-rounds 至少为 1")
    private Integer maxRounds;

    /** 上下文 Redis TTL（分钟） */
    @NotNull(message = "ttl-minutes 不能为空")
    @Min(value = 1, message = "ttl-minutes 至少为 1")
    private Integer ttlMinutes;

    /** 用户级限流（条/分钟） */
    @NotNull(message = "rate-limit-per-minute 不能为空")
    @Min(value = 1, message = "rate-limit-per-minute 至少为 1")
    private Integer rateLimitPerMinute;
}
