package com.mall.ai.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * AI 智能客服模块配置入口。
 * <p>启用 {@link AiConfigProperties} 属性绑定与异步线程池。</p>
 */
@Configuration
@EnableAsync
@EnableConfigurationProperties(AiConfigProperties.class)
public class AiConfig {

    /**
     * AI 模块专用异步线程池（用于消息持久化等）。
     */
    @Bean("aiTaskExecutor")
    public Executor aiTaskExecutor(AiConfigProperties props) {
        return buildExecutor(props, "ai-async-");
    }

    /**
     * AI 聊天处理线程池：SSE 流式编排（LLM 请求 + 工具调用）在此执行，
     * 避免长时间流式输出阻塞 Servlet 线程。
     */
    @Bean("aiChatExecutor")
    public Executor aiChatExecutor(AiConfigProperties props) {
        return buildExecutor(props, "ai-chat-");
    }

    private Executor buildExecutor(AiConfigProperties props, String threadNamePrefix) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(props.getAsyncCorePoolSize());
        executor.setMaxPoolSize(props.getAsyncMaxPoolSize());
        executor.setQueueCapacity(props.getAsyncQueueCapacity());
        executor.setThreadNamePrefix(threadNamePrefix);
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.initialize();
        return executor;
    }
}
