package com.mall.admin.dto;

import lombok.Data;

import java.math.BigDecimal;

/**
 * 管理端 Top10 商品销量榜行 VO（含对比上一同期的销量增幅）。
 */
@Data
public class AdminTopProductVO {

    /** 商品ID */
    private Long productId;

    /** 商品名称（下单时快照） */
    private String productName;

    /** 本期销量 */
    private Long sales;

    /** 本期销售额 */
    private BigDecimal amount;

    /**
     * 销量增幅百分比 =（本期-上期)/上期*100，保留 1 位小数；
     * 上期销量为 0 时为 null（前端显示 NEW）。
     */
    private BigDecimal growth;
}
