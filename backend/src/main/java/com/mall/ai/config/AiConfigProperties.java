package com.mall.ai.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * AI 智能客服配置属性。
 * <p>绑定前缀 {@code mall.ai}，由 {@link AiConfig} 注册。结构：</p>
 * <pre>
 * mall:
 *   ai:
 *     enabled: false          # 未配置 api-key 时保持关闭，接口返回友好提示
 *     llm: { base-url, api-key, model, temperature, connect-timeout-seconds }
 *     context: { max-rounds, ttl-minutes }
 *     limit: { rate-limit-per-minute }
 * </pre>
 */
@Data
@Validated
@ConfigurationProperties(prefix = "mall.ai")
public class AiConfigProperties {

    /** 是否启用 AI 客服（api-key 为空时视为未启用） */
    private boolean enabled = false;

    @Valid
    @NotNull
    private Llm llm = new Llm();

    @Valid
    @NotNull
    private Context context = new Context();

    @Valid
    @NotNull
    private Limit limit = new Limit();

    /** 异步线程池：核心线程数 */
    @Positive
    private int asyncCorePoolSize = 4;

    /** 异步线程池：最大线程数 */
    @Positive
    private int asyncMaxPoolSize = 8;

    /** 异步线程池：队列容量 */
    @Positive
    private int asyncQueueCapacity = 100;

    /**
     * 判断 AI 客服是否可用：enabled=true 且 api-key 非空。
     */
    public boolean available() {
        return enabled && llm.getApiKey() != null && !llm.getApiKey().isBlank();
    }

    /**
     * LLM 服务端点配置（OpenAI 兼容接口）。
     */
    @Data
    public static class Llm {

        /** 完整 chat completions 端点 URL，如 https://api.deepseek.com/v1/chat/completions */
        @NotBlank
        private String baseUrl;

        /** API Key（开发环境可为空，生产通过环境变量 MALL_AI_API_KEY 注入） */
        private String apiKey = "";

        /** 模型名称，如 deepseek-chat */
        @NotBlank
        private String model;

        /** 温度（0-2，越大越发散） */
        @NotNull
        private Double temperature = 0.7;

        /** 建立连接超时（秒）；流式输出整体耗时不受此限 */
        @Positive
        private int connectTimeoutSeconds = 10;
    }

    /**
     * 多轮上下文配置（Redis 缓存）。
     */
    @Data
    public static class Context {

        /** 保留最近 N 轮对话（一轮 = user + assistant） */
        @Positive
        private int maxRounds = 10;

        /** 上下文 Redis TTL（分钟） */
        @Positive
        private int ttlMinutes = 30;
    }

    /**
     * 限流配置。
     */
    @Data
    public static class Limit {

        /** 用户级限流：每分钟最大消息数（固定窗口） */
        @Positive
        private int rateLimitPerMinute = 10;
    }
}
