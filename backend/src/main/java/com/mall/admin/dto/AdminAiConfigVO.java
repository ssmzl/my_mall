package com.mall.admin.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 管理端 AI 配置视图（api-key 脱敏：仅返回 hasApiKey 标志，不返回明文）。
 */
@Data
public class AdminAiConfigVO {

    /** 是否启用 AI 客服 */
    private Boolean enabled;

    /** LLM chat completions 端点 URL */
    private String baseUrl;

    /** 是否已配置 api-key（脱敏：明文不回传） */
    private Boolean hasApiKey;

    /** 模型名称 */
    private String model;

    /** 温度（0-2） */
    private BigDecimal temperature;

    /** 上下文保留轮数 */
    private Integer maxRounds;

    /** 上下文 Redis TTL（分钟） */
    private Integer ttlMinutes;

    /** 用户级限流（条/分钟） */
    private Integer rateLimitPerMinute;

    /** 配置最后更新时间（来自 yml 默认配置时为 null） */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime updateTime;
}
