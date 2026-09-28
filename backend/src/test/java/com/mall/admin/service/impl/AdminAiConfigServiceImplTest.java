package com.mall.admin.service.impl;

import com.mall.admin.dto.AdminAiConfigUpdateDTO;
import com.mall.admin.dto.AdminAiConfigVO;
import com.mall.ai.config.AiConfigProperties;
import com.mall.ai.entity.AiConfigEntity;
import com.mall.ai.mapper.AiConfigMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 管理端 AI 配置单元测试：api-key 脱敏（仅返回 hasApiKey，VO 无明文字段）、
 * 留空保留原值（表行优先、其次 yml 值）、新 key 去空白、
 * upsert 分支（表无行 insert / 有行 updateById）与保存后原地覆盖 props 即时生效。
 */
@ExtendWith(MockitoExtension.class)
class AdminAiConfigServiceImplTest {

    @Mock
    private AiConfigMapper aiConfigMapper;

    private AiConfigProperties props;
    private AdminAiConfigServiceImpl service;

    @BeforeEach
    void setUp() {
        props = new AiConfigProperties();
        props.setEnabled(true);
        props.getLlm().setBaseUrl("https://api.deepseek.com/v1/chat/completions");
        props.getLlm().setApiKey("yml-key");
        props.getLlm().setModel("deepseek-chat");
        service = new AdminAiConfigServiceImpl(aiConfigMapper, props);
    }

    // ---------- get：脱敏 ----------

    @Test
    void getShouldFallBackToYmlWhenRowMissing() {
        when(aiConfigMapper.selectById(1L)).thenReturn(null);

        AdminAiConfigVO vo = service.get();

        assertThat(vo.getEnabled()).isTrue();
        assertThat(vo.getBaseUrl()).isEqualTo(props.getLlm().getBaseUrl());
        assertThat(vo.getModel()).isEqualTo("deepseek-chat");
        assertThat(vo.getHasApiKey()).isTrue();
        assertThat(vo.getTemperature()).isEqualByComparingTo("0.7");
        assertThat(vo.getMaxRounds()).isEqualTo(10);
        assertThat(vo.getTtlMinutes()).isEqualTo(30);
        assertThat(vo.getRateLimitPerMinute()).isEqualTo(10);
        assertThat(vo.getUpdateTime()).isNull();
    }

    @Test
    void getShouldReportNoApiKeyWhenYmlKeyBlank() {
        props.getLlm().setApiKey("  ");
        when(aiConfigMapper.selectById(1L)).thenReturn(null);

        AdminAiConfigVO vo = service.get();

        assertThat(vo.getHasApiKey()).isFalse();
    }

    @Test
    void getShouldMaskApiKeyFromRow() {
        when(aiConfigMapper.selectById(1L)).thenReturn(row("db-key"));

        AdminAiConfigVO vo = service.get();

        // 脱敏：只给 hasApiKey 标志；VO 上不存在任何明文 api-key 字段
        assertThat(vo.getHasApiKey()).isTrue();
        assertThatThrownBy(() -> AdminAiConfigVO.class.getDeclaredField("apiKey"))
                .isInstanceOf(NoSuchFieldException.class);
        assertThat(vo.getModel()).isEqualTo("db-model");
        assertThat(vo.getUpdateTime()).isEqualTo(UPDATE_TIME);
    }

    // ---------- update：留空保留 / 新值去空白 ----------

    @Test
    void updateShouldKeepRowApiKeyWhenBlank() {
        when(aiConfigMapper.selectById(1L)).thenReturn(row("old-key"));

        service.update(dto("  "));

        ArgumentCaptor<AiConfigEntity> captor = ArgumentCaptor.forClass(AiConfigEntity.class);
        verify(aiConfigMapper).updateById(captor.capture());
        assertThat(captor.getValue().getApiKey()).isEqualTo("old-key");
        // 即时生效：props 的 api-key 也应保持为原值
        assertThat(props.getLlm().getApiKey()).isEqualTo("old-key");
    }

    @Test
    void updateShouldKeepYmlApiKeyWhenRowMissingAndBlank() {
        when(aiConfigMapper.selectById(1L)).thenReturn(null);

        service.update(dto("   "));

        ArgumentCaptor<AiConfigEntity> captor = ArgumentCaptor.forClass(AiConfigEntity.class);
        verify(aiConfigMapper).insert(captor.capture());
        assertThat(captor.getValue().getApiKey()).isEqualTo("yml-key");
        assertThat(props.getLlm().getApiKey()).isEqualTo("yml-key");
    }

    @Test
    void updateShouldUseTrimmedNewApiKey() {
        when(aiConfigMapper.selectById(1L)).thenReturn(row("old-key"));

        service.update(dto("  new-key  "));

        ArgumentCaptor<AiConfigEntity> captor = ArgumentCaptor.forClass(AiConfigEntity.class);
        verify(aiConfigMapper).updateById(captor.capture());
        assertThat(captor.getValue().getApiKey()).isEqualTo("new-key");
        assertThat(props.getLlm().getApiKey()).isEqualTo("new-key");
    }

    // ---------- update：upsert 分支与即时生效 ----------

    @Test
    void updateShouldInsertWhenRowMissing() {
        when(aiConfigMapper.selectById(1L)).thenReturn(null);

        service.update(dto("k"));

        verify(aiConfigMapper).insert(any(AiConfigEntity.class));
        verify(aiConfigMapper, never()).updateById(any(AiConfigEntity.class));
    }

    @Test
    void updateShouldUpsertWithFixedIdAndApplyToPropsImmediately() {
        when(aiConfigMapper.selectById(1L)).thenReturn(row("old-key"));
        AdminAiConfigUpdateDTO dto = dto("new-key");
        dto.setEnabled(false);
        dto.setBaseUrl("  https://new.example.com/v1/chat/completions  ");
        dto.setModel("  gpt-4o-mini  ");
        dto.setTemperature(new BigDecimal("0.30"));
        dto.setMaxRounds(5);
        dto.setTtlMinutes(15);
        dto.setRateLimitPerMinute(20);

        service.update(dto);

        ArgumentCaptor<AiConfigEntity> captor = ArgumentCaptor.forClass(AiConfigEntity.class);
        verify(aiConfigMapper).updateById(captor.capture());
        AiConfigEntity saved = captor.getValue();
        assertThat(saved.getId()).isEqualTo(1L);
        assertThat(saved.getEnabled()).isZero(); // enabled=false → 0
        assertThat(saved.getBaseUrl()).isEqualTo("https://new.example.com/v1/chat/completions");
        assertThat(saved.getModel()).isEqualTo("gpt-4o-mini");

        // 保存后原地覆盖 props 单例，下一次 AI 对话即按新配置执行
        assertThat(props.isEnabled()).isFalse();
        assertThat(props.getLlm().getBaseUrl()).isEqualTo("https://new.example.com/v1/chat/completions");
        assertThat(props.getLlm().getModel()).isEqualTo("gpt-4o-mini");
        assertThat(props.getLlm().getTemperature()).isEqualTo(0.30);
        assertThat(props.getContext().getMaxRounds()).isEqualTo(5);
        assertThat(props.getContext().getTtlMinutes()).isEqualTo(15);
        assertThat(props.getLimit().getRateLimitPerMinute()).isEqualTo(20);
    }

    // ---------- 辅助 ----------

    private static final LocalDateTime UPDATE_TIME = LocalDateTime.of(2026, 9, 28, 10, 0);

    private static AiConfigEntity row(String apiKey) {
        AiConfigEntity row = new AiConfigEntity();
        row.setId(1L);
        row.setEnabled(1);
        row.setBaseUrl("https://db.example.com/v1/chat/completions");
        row.setApiKey(apiKey);
        row.setModel("db-model");
        row.setTemperature(new BigDecimal("0.5"));
        row.setMaxRounds(8);
        row.setTtlMinutes(20);
        row.setRateLimitPerMinute(15);
        row.setUpdateTime(UPDATE_TIME);
        return row;
    }

    private static AdminAiConfigUpdateDTO dto(String apiKey) {
        AdminAiConfigUpdateDTO dto = new AdminAiConfigUpdateDTO();
        dto.setEnabled(true);
        dto.setBaseUrl("https://api.deepseek.com/v1/chat/completions");
        dto.setApiKey(apiKey);
        dto.setModel("deepseek-chat");
        dto.setTemperature(new BigDecimal("0.7"));
        dto.setMaxRounds(10);
        dto.setTtlMinutes(30);
        dto.setRateLimitPerMinute(10);
        return dto;
    }
}
