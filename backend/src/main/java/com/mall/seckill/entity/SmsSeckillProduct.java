package com.mall.seckill.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 秒杀商品表 sms_seckill_product 实体。
 */
@Data
@TableName("sms_seckill_product")
public class SmsSeckillProduct implements Serializable {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 秒杀活动ID */
    private Long activityId;

    /** 商品ID */
    private Long productId;

    /** 参与秒杀的SKU ID */
    private Long skuId;

    /** 秒杀价（元） */
    private BigDecimal seckillPrice;

    /** 秒杀库存（非负） */
    private Integer seckillStock;

    /** 每人限购件数 */
    private Integer limitPerUser;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;
}
