package com.mall.ai.controller;

import com.mall.ai.config.AiConfigProperties;
import com.mall.ai.dto.AiChatDTO;
import com.mall.ai.service.ChatService;
import com.mall.common.BizException;
import com.mall.security.AuthInterceptor;
import com.mall.security.LoginUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.Executor;

/**
 * AI 智能客服接口（均需登录，用户 ID 取自登录上下文）。
 * <p>聊天走 SSE 流式推送：控制器创建 {@link SseEmitter} 后立即返回，
 * 编排（LLM 请求 + 工具调用 + 事件推送）提交到 {@code aiChatExecutor} 异步执行。</p>
 */
@Slf4j
@Tag(name = "AI 客服", description = "AI 智能客服 SSE 流式聊天")
@RestController
@RequestMapping("/ai")
public class AiChatController {

    /** 用户级限流 Redis key 前缀（固定窗口 1 分钟） */
    private static final String RATE_LIMIT_KEY_PREFIX = "mall:ai:limit:";

    /** SSE 超时时间（毫秒）：一次含工具调用的流式对话上限 */
    private static final long SSE_TIMEOUT_MILLIS = 120_000L;

    /** 限流超限业务错误码（HTTP 429 由全局异常处理器按此码映射） */
    private static final int CODE_TOO_MANY_REQUESTS = 429;

    private final ChatService chatService;
    private final StringRedisTemplate stringRedisTemplate;
    private final AiConfigProperties aiProps;
    private final Executor aiChatExecutor;

    public AiChatController(ChatService chatService, StringRedisTemplate stringRedisTemplate,
                            AiConfigProperties aiProps,
                            @org.springframework.beans.factory.annotation.Qualifier("aiChatExecutor") Executor aiChatExecutor) {
        this.chatService = chatService;
        this.stringRedisTemplate = stringRedisTemplate;
        this.aiProps = aiProps;
        this.aiChatExecutor = aiChatExecutor;
    }

    @Operation(summary = "AI 聊天（SSE 流式）",
            description = "事件类型：message 文本增量 / tool_call 工具调用开始 / tool_result 工具结果 / done 结束 / error 异常")
    @PostMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter chatStream(@Valid @RequestBody AiChatDTO dto) {
        Long userId = LoginUser.getCurrentUserId();
        checkRateLimit(userId);

        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT_MILLIS);
        // AI 未启用：按角色差异化提示（管理员提示配置入口，普通用户仅提示暂未开放，不暴露配置细节）
        if (!aiProps.available()) {
            sendDisabledNotice(emitter);
            return emitter;
        }
        aiChatExecutor.execute(() -> chatService.streamChat(userId, dto.getMessage(), emitter));
        return emitter;
    }

    /** AI 未启用提示：管理员指向后台配置页，普通用户仅提示功能暂未开放 */
    private void sendDisabledNotice(SseEmitter emitter) {
        LoginUser loginUser = LoginUser.get();
        boolean admin = loginUser != null && AuthInterceptor.ROLE_ADMIN == loginUser.getRole();
        String tip = admin
                ? "AI 客服未启用：请前往「后台管理 → AI 配置」启用并填写 api-key"
                : "AI 客服暂未开放，请稍后再试";
        try {
            emitter.send(SseEmitter.event().name("error").data(tip));
        } catch (IOException e) {
            log.warn("AI 未启用提示发送失败: {}", e.getMessage());
        }
        emitter.complete();
    }

    /** 固定窗口限流：Redis 计数 key 首次写入时设 1 分钟过期，超阈值抛 429 */
    private void checkRateLimit(Long userId) {
        String rateKey = RATE_LIMIT_KEY_PREFIX + userId;
        Long count = stringRedisTemplate.opsForValue().increment(rateKey);
        if (count == null) {
            return;
        }
        if (count == 1) {
            stringRedisTemplate.expire(rateKey, Duration.ofMinutes(1));
        }
        if (count > aiProps.getLimit().getRateLimitPerMinute()) {
            log.warn("AI 聊天触发限流: userId={}, count={}（阈值 {}）",
                    userId, count, aiProps.getLimit().getRateLimitPerMinute());
            throw new BizException(CODE_TOO_MANY_REQUESTS, "发送太频繁，请稍后再试");
        }
    }
}
