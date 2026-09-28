package com.mall.ai.config;

import com.mall.ai.entity.AiConfigEntity;

/**
 * {@code ai_config} 表行 → {@link AiConfigProperties} 的覆盖规则（启动加载与管理端保存共用）。
 * <p>api-key 为空/空白时不覆盖（保留 props 现值），避免把密钥冲掉。</p>
 */
public final class AiConfigApplier {

    private AiConfigApplier() {
    }

    /**
     * 将配置表行原地覆盖到 {@link AiConfigProperties} 单例（调用方负责并发控制）。
     */
    public static void apply(AiConfigProperties props, AiConfigEntity row) {
        props.setEnabled(Integer.valueOf(1).equals(row.getEnabled()));
        props.getLlm().setBaseUrl(row.getBaseUrl());
        if (row.getApiKey() != null && !row.getApiKey().isBlank()) {
            props.getLlm().setApiKey(row.getApiKey());
        }
        props.getLlm().setModel(row.getModel());
        if (row.getTemperature() != null) {
            props.getLlm().setTemperature(row.getTemperature().doubleValue());
        }
        if (row.getMaxRounds() != null) {
            props.getContext().setMaxRounds(row.getMaxRounds());
        }
        if (row.getTtlMinutes() != null) {
            props.getContext().setTtlMinutes(row.getTtlMinutes());
        }
        if (row.getRateLimitPerMinute() != null) {
            props.getLimit().setRateLimitPerMinute(row.getRateLimitPerMinute());
        }
    }
}
