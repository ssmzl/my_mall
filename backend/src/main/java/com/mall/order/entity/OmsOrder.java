package com.mall.order.entity;

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
 * 订单表 oms_order 实体。
 */
@Data
@TableName("oms_order")
public class OmsOrder implements Serializable {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 订单编号（全局唯一） */
    private String orderNo;

    private Long userId;

    /** 订单总金额（元） */
    private BigDecimal totalAmount;

    /** 状态：0待支付 1已支付 2已发货 3已完成 4已取消 */
    private Integer status;

    /** 收货地址快照（JSON） */
    private String addressSnapshot;

    /** 支付时间 */
    private LocalDateTime payTime;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
}
