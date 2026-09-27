package com.mall.ai.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * AI 聊天请求体。
 */
@Data
public class AiChatDTO {

    /** 用户发送的聊天内容 */
    @NotBlank(message = "消息内容不能为空")
    @Size(max = 500, message = "消息长度不能超过 500 字")
    private String message;
}
