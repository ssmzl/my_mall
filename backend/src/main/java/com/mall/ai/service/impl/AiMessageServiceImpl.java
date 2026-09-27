package com.mall.ai.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.mall.ai.entity.AiConversation;
import com.mall.ai.entity.AiMessage;
import com.mall.ai.mapper.AiConversationMapper;
import com.mall.ai.mapper.AiMessageMapper;
import com.mall.ai.service.AiMessageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

/**
 * AI 聊天消息持久化实现：跑在 {@code aiTaskExecutor} 异步线程池，失败仅记录日志。
 * <p>每个用户一条会话（首次对话自动创建）；一轮对话按 user → assistant(tool_calls) →
 * tool → assistant(总结) 顺序落库，与 LLM 消息链路一一对应。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AiMessageServiceImpl implements AiMessageService {

    /** 会话标题最大长度 */
    private static final int TITLE_MAX_LENGTH = 20;

    private final AiConversationMapper conversationMapper;
    private final AiMessageMapper messageMapper;

    @Async("aiTaskExecutor")
    @Override
    public void saveRound(Long userId, String userMessage, String assistantReply,
                          String toolCallsJson, String toolResultJson) {
        try {
            AiConversation conversation = getOrCreateConversation(userId, userMessage);
            insertMessage(conversation.getId(), AiMessage.ROLE_USER, userMessage, null, null);
            if (toolCallsJson != null) {
                insertMessage(conversation.getId(), AiMessage.ROLE_ASSISTANT, null, toolCallsJson, null);
                insertMessage(conversation.getId(), AiMessage.ROLE_TOOL, null, null, toolResultJson);
            }
            insertMessage(conversation.getId(), AiMessage.ROLE_ASSISTANT, assistantReply, null, null);
        } catch (Exception e) {
            log.error("[AI] 聊天记录持久化失败, userId={}, message={}, error={}",
                    userId, userMessage, e.getMessage(), e);
        }
    }

    /** 查询用户最近一条会话，不存在则创建（标题取用户第一句话前 20 字） */
    private AiConversation getOrCreateConversation(Long userId, String firstMessage) {
        AiConversation conversation = conversationMapper.selectOne(
                new LambdaQueryWrapper<AiConversation>()
                        .eq(AiConversation::getUserId, userId)
                        .orderByDesc(AiConversation::getUpdateTime)
                        .last("LIMIT 1"));
        if (conversation == null) {
            conversation = new AiConversation();
            conversation.setUserId(userId);
            conversation.setTitle(firstMessage.length() > TITLE_MAX_LENGTH
                    ? firstMessage.substring(0, TITLE_MAX_LENGTH) : firstMessage);
            conversationMapper.insert(conversation);
            log.info("[AI] 创建新会话, userId={}, conversationId={}, title={}",
                    userId, conversation.getId(), conversation.getTitle());
        }
        return conversation;
    }

    private void insertMessage(Long conversationId, String role, String content,
                               String toolCalls, String toolResult) {
        AiMessage message = new AiMessage();
        message.setConversationId(conversationId);
        message.setRole(role);
        message.setContent(content);
        message.setToolCalls(toolCalls);
        message.setToolResult(toolResult);
        messageMapper.insert(message);
    }
}
