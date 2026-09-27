package com.mall.ai.service;

/**
 * AI 聊天消息异步持久化服务。
 */
public interface AiMessageService {

    /**
     * 异步持久化一轮对话（用户消息 + AI 回复链路），失败仅记录日志、不抛出。
     * <p>首次对话自动创建会话（标题取用户第一句话前 20 字）。</p>
     *
     * @param userId          当前登录用户 ID
     * @param userMessage     用户输入
     * @param assistantReply  AI 最终回复全文
     * @param toolCallsJson   LLM 触发的 tool_calls JSON（未触发为 null）
     * @param toolResultJson  工具执行结果 JSON（未触发为 null）
     */
    void saveRound(Long userId, String userMessage, String assistantReply,
                   String toolCallsJson, String toolResultJson);
}
