package com.mall.admin.dto;

import lombok.Data;

import java.math.BigDecimal;

/**
 * 管理端数据看板汇总 VO。
 * <p>销售口径：oms_order.status IN (1,2,3)（已支付/已发货/已完成），时间按 pay_time；
 * AI 轮数口径：ai_message.role='user' 的消息数（一条 user 消息 = 一轮）；
 * AI token 口径：ai_message 中 prompt_tokens + completion_tokens 求和（NULL 按 0）。</p>
 */
@Data
public class AdminStatsSummaryVO {

    /** 今日销量（今日 pay_time 已支付订单件数） */
    private Long todaySales;

    /** 今日销售额 */
    private BigDecimal todayAmount;

    /** 累计销量 */
    private Long totalSales;

    /** 累计销售额 */
    private BigDecimal totalAmount;

    /** 今日 AI 对话轮数 */
    private Long todayAiRounds;

    /** 累计 AI 对话轮数 */
    private Long totalAiRounds;

    /** 今日 AI token 消耗（prompt + completion） */
    private Long todayAiTokens;

    /** 累计 AI token 消耗 */
    private Long totalAiTokens;
}
