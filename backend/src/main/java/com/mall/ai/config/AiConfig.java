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
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(props.getAsyncCorePoolSize());
        executor.setMaxPoolSize(props.getAsyncMaxPoolSize());
        executor.setQueueCapacity(props.getAsyncQueueCapacity());
        executor.setThreadNamePrefix("ai-async-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.initialize();
        return executor;
    }
}
