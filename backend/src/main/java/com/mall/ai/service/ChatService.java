package com.mall.ai.service;

import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * AI 聊天编排服务：上下文管理 → LLM 流式请求 → 工具调用回填 → 二次总结 → SSE 推送。
 */
public interface ChatService {

    /**
     * 处理一轮聊天并向前端流式推送：
     * <ol>
     *   <li>读取 Redis 多轮上下文，拼装 system prompt + 历史 + 当前 user message；</li>
     *   <li>携带 tools 请求 LLM（stream=true），增量文本以 {@code message} 事件推送；</li>
     *   <li>若返回 finish_reason=tool_calls，串行执行工具（{@code tool_call}/{@code tool_result} 事件），
     *       将 tool 消息回填后再次请求 LLM 总结；</li>
     *   <li>结束后以 FIFO 截断写回 Redis 上下文，异步持久化消息，发送 {@code done} 事件。</li>
     * </ol>
     * 任何异常均以 {@code error} 事件终止，不向上抛出（调用方在异步线程中无法捕获）。
     *
     * @param userId   当前登录用户 ID
     * @param message  用户输入
     * @param emitter  控制器创建的 SSE 发射器
     */
    void streamChat(Long userId, String message, SseEmitter emitter);
}
