package com.mall.admin.service;

import com.mall.admin.dto.AdminAiConfigUpdateDTO;
import com.mall.admin.dto.AdminAiConfigVO;

/**
 * 管理端 AI 配置服务：查看（api-key 脱敏）与修改（留空保留原值、保存后即时生效）。
 */
public interface AdminAiConfigService {

    /**
     * 查询当前生效的 AI 配置（api-key 脱敏：只返回 hasApiKey 布尔，不返回明文）。
     * <p>优先返回 ai_config 表配置；表无行时返回 yml 默认配置。</p>
     */
    AdminAiConfigVO get();

    /**
     * 保存 AI 配置：写库后原地更新 {@code AiConfigProperties} 单例，立即生效无需重启。
     * <p>api-key 传空表示保留原值（表已有值或当前 yml 值）。</p>
     *
     * @param dto 校验后的配置项
     */
    void update(AdminAiConfigUpdateDTO dto);
}
