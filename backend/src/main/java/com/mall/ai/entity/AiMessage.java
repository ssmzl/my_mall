package com.mall.ai.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * AI 客服消息表 ai_message 实体。
 */
@Data
@TableName("ai_message")
public class AiMessage implements Serializable {

    /** 角色：用户消息 */
    public static final String ROLE_USER = "user";
    /** 角色：AI 回复 */
    public static final String ROLE_ASSISTANT = "assistant";
    /** 角色：工具执行结果 */
    public static final String ROLE_TOOL = "tool";

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 所属会话ID */
    private Long conversationId;

    /** 角色：user / assistant / tool */
    private String role;

    /** 消息内容（tool 角色为空，内容在 toolResult） */
    private String content;

    /** LLM 触发的 tool_calls JSON（仅 assistant 角色且触发工具调用时非空） */
    private String toolCalls;

    /** 工具执行结果 JSON（仅 tool 角色非空） */
    private String toolResult;

    /** 该轮 LLM 消耗的 prompt tokens（仅记在每轮最终 assistant 消息上，LLM 未返回时为 NULL） */
    private Integer promptTokens;

    /** 该轮 LLM 消耗的 completion tokens（含义同 promptTokens） */
    private Integer completionTokens;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;
}
