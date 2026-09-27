package com.mall.admin.service.impl;

import com.mall.admin.dto.AdminAiConfigUpdateDTO;
import com.mall.admin.dto.AdminAiConfigVO;
import com.mall.admin.service.AdminAiConfigService;
import com.mall.ai.config.AiConfigApplier;
import com.mall.ai.config.AiConfigInitializer;
import com.mall.ai.config.AiConfigProperties;
import com.mall.ai.entity.AiConfigEntity;
import com.mall.ai.mapper.AiConfigMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;

/**
 * 管理端 AI 配置实现。
 * <p>保存流程：api-key 留空时取原值（表行优先，其次 yml 值）→ upsert ai_config 表（id=1）→
 * {@link AiConfigApplier#apply} 原地更新 {@link AiConfigProperties} 单例即时生效。
 * {@code update} 加 synchronized 防止并发保存互相覆盖（覆盖 props 与查原值需原子）。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AdminAiConfigServiceImpl implements AdminAiConfigService {

    private final AiConfigMapper aiConfigMapper;
    private final AiConfigProperties props;

    @Override
    public AdminAiConfigVO get() {
        AiConfigEntity row = aiConfigMapper.selectById(AiConfigInitializer.CONFIG_ID);
        if (row == null) {
            return fromProps();
        }
        return fromRow(row);
    }

    @Override
    public synchronized void update(AdminAiConfigUpdateDTO dto) {
        AiConfigEntity row = aiConfigMapper.selectById(AiConfigInitializer.CONFIG_ID);
        // api-key 留空表示保留原值：优先表行已有值，表无行时保留 yml 值
        String apiKey = dto.getApiKey() != null && !dto.getApiKey().isBlank()
                ? dto.getApiKey().trim()
                : (row != null ? row.getApiKey() : props.getLlm().getApiKey());

        AiConfigEntity entity = new AiConfigEntity();
        entity.setId(AiConfigInitializer.CONFIG_ID);
        entity.setEnabled(Boolean.TRUE.equals(dto.getEnabled()) ? 1 : 0);
        entity.setBaseUrl(dto.getBaseUrl().trim());
        entity.setApiKey(apiKey);
        entity.setModel(dto.getModel().trim());
        entity.setTemperature(dto.getTemperature());
        entity.setMaxRounds(dto.getMaxRounds());
        entity.setTtlMinutes(dto.getTtlMinutes());
        entity.setRateLimitPerMinute(dto.getRateLimitPerMinute());

        if (row == null) {
            aiConfigMapper.insert(entity);
        } else {
            aiConfigMapper.updateById(entity);
        }

        // 原地更新配置单例，下一次 AI 对话即按新配置执行（无需重启）
        AiConfigApplier.apply(props, entity);
        log.info("[AI] 管理端已保存 AI 配置, enabled={}, model={}, baseUrl={}",
                props.isEnabled(), props.getLlm().getModel(), props.getLlm().getBaseUrl());
    }

    /** ai_config 表无行时按 yml 默认值组装 VO */
    private AdminAiConfigVO fromProps() {
        AdminAiConfigVO vo = new AdminAiConfigVO();
        vo.setEnabled(props.isEnabled());
        vo.setBaseUrl(props.getLlm().getBaseUrl());
        vo.setHasApiKey(props.getLlm().getApiKey() != null && !props.getLlm().getApiKey().isBlank());
        vo.setModel(props.getLlm().getModel());
        vo.setTemperature(BigDecimal.valueOf(props.getLlm().getTemperature()));
        vo.setMaxRounds(props.getContext().getMaxRounds());
        vo.setTtlMinutes(props.getContext().getTtlMinutes());
        vo.setRateLimitPerMinute(props.getLimit().getRateLimitPerMinute());
        return vo;
    }

    /** 按配置表行组装 VO（api-key 脱敏，仅返回 hasApiKey） */
    private AdminAiConfigVO fromRow(AiConfigEntity row) {
        AdminAiConfigVO vo = new AdminAiConfigVO();
        vo.setEnabled(row.isEnabled());
        vo.setBaseUrl(row.getBaseUrl());
        vo.setHasApiKey(row.getApiKey() != null && !row.getApiKey().isBlank());
        vo.setModel(row.getModel());
        vo.setTemperature(row.getTemperature());
        vo.setMaxRounds(row.getMaxRounds());
        vo.setTtlMinutes(row.getTtlMinutes());
        vo.setRateLimitPerMinute(row.getRateLimitPerMinute());
        vo.setUpdateTime(row.getUpdateTime());
        return vo;
    }
}
