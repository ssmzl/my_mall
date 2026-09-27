package com.mall.ai.config;

import com.mall.ai.entity.AiConfigEntity;
import com.mall.ai.mapper.AiConfigMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * 应用启动时加载 {@code ai_config} 表（id=1）配置覆盖 {@link AiConfigProperties}，
 * 使管理端保存的配置重启后仍生效；表无行时沿用 yml 默认配置。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AiConfigInitializer implements ApplicationRunner {

    /** 单行配置主键 */
    public static final long CONFIG_ID = 1L;

    private final AiConfigMapper aiConfigMapper;
    private final AiConfigProperties props;

    @Override
    public void run(ApplicationArguments args) {
        AiConfigEntity row = aiConfigMapper.selectById(CONFIG_ID);
        if (row == null) {
            log.info("[AI] ai_config 表无配置行，沿用 yml 默认配置, model={}, enabled={}",
                    props.getLlm().getModel(), props.isEnabled());
            return;
        }
        AiConfigApplier.apply(props, row);
        log.info("[AI] 已从 ai_config 表加载配置覆盖 yml, model={}, enabled={}, baseUrl={}",
                props.getLlm().getModel(), props.isEnabled(), props.getLlm().getBaseUrl());
    }
}
