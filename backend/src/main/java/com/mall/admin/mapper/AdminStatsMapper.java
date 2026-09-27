package com.mall.admin.mapper;

import com.mall.admin.dto.ProductSalesDTO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 管理端数据看板统计 Mapper（多表聚合查询，SQL 手写 XML）。
 * <p>时间参数为左闭右开区间 [start, end)；start/end 为 null 表示不限时间（累计口径）。</p>
 */
@Mapper
public interface AdminStatsMapper {

    /**
     * 订单销量/销售额汇总：SUM(oi.quantity)、SUM(oi.price * oi.quantity)。
     * <p>仅统计已支付订单（oms_order.status IN (1,2,3)），时间按 pay_time 过滤。</p>
     */
    ProductSalesDTO selectOrderSales(@Param("start") LocalDateTime start,
                                     @Param("end") LocalDateTime end);

    /**
     * AI 对话轮数：ai_message.role='user' 的消息数（一条 user 消息 = 一轮），按 create_time 过滤。
     */
    Long selectAiRounds(@Param("start") LocalDateTime start,
                        @Param("end") LocalDateTime end);

    /**
     * AI token 消耗：SUM(prompt_tokens + completion_tokens)，NULL 按 0。
     */
    Long selectAiTokens(@Param("start") LocalDateTime start,
                        @Param("end") LocalDateTime end);

    /**
     * 商品销量 Top10：按 pay_time 所在区间聚合，销量倒序取前 10。
     */
    List<ProductSalesDTO> selectTopProducts(@Param("start") LocalDateTime start,
                                            @Param("end") LocalDateTime end);
}
