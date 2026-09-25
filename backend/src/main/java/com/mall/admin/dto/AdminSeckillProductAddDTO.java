package com.mall.admin.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import lombok.Data;

import java.math.BigDecimal;

/**
 * 管理端新增秒杀商品请求（与前端 AdminSeckillProductPayload 契约对齐）。
 */
@Data
public class AdminSeckillProductAddDTO {

    /** 秒杀活动ID */
    @NotNull(message = "活动不能为空")
    private Long activityId;

    /** 商品ID */
    @NotNull(message = "商品不能为空")
    private Long productId;

    /** 参与秒杀的 SKU ID（必须属于该商品） */
    @NotNull(message = "SKU不能为空")
    private Long skuId;

    /** 秒杀价（元） */
    @NotNull(message = "秒杀价不能为空")
    @DecimalMin(value = "0.01", message = "秒杀价必须大于0")
    private BigDecimal seckillPrice;

    /** 秒杀库存（非负） */
    @NotNull(message = "秒杀库存不能为空")
    @PositiveOrZero(message = "秒杀库存不能为负数")
    private Integer seckillStock;

    /** 每人限购件数，缺省 1 */
    private Integer limitPerUser;
}
