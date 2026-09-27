package com.mall.ai.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * AI 客服配置表 ai_config 实体（单行配置，主键固定为 1）。
 * <p>表中有配置行时，应用启动与管理端保存后覆盖 {@code AiConfigProperties} 对应字段；
 * 表无行时沿用 yml 默认配置。</p>
 */
@Data
@TableName("ai_config")
public class AiConfigEntity implements Serializable {

    /** 主键，固定为 1（单行配置） */
    @TableId
    private Long id;

    /** 是否启用 AI 客服：0禁用 1启用 */
    private Integer enabled;

    /** LLM chat completions 端点 URL */
    private String baseUrl;

    /** LLM API Key（管理端响应脱敏，留空保存表示保留原值） */
    private String apiKey;

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

    private LocalDateTime updateTime;

    public boolean isEnabled() {
        return enabled != null && enabled == 1;
    }
}
