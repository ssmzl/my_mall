package com.mall.seckill.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.math.BigDecimal;

/**
 * 秒杀商品 VO（与前端 SeckillProduct 契约一致）。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class SeckillProductVO implements Serializable {

    private Long id;

    private Long activityId;

    private Long productId;

    private Long skuId;

    /** 商品名称快照 */
    private String productName;

    /** 商品主图 */
    private String mainPic;

    /** 秒杀价（元） */
    private BigDecimal seckillPrice;

    /** 原价（元，取商品展示价） */
    private BigDecimal originalPrice;

    /** 秒杀剩余库存（实时，优先取 Redis 预扣计数） */
    private Integer seckillStock;

    /** 每人限购件数 */
    private Integer limitPerUser;
}
