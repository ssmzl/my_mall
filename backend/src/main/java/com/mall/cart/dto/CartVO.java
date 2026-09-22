package com.mall.cart.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

/**
 * 购物车聚合视图，与前端 CartVO 契约对齐。
 */
@Data
public class CartVO {

    private List<CartItemVO> items;

    /** 总件数（各条目 quantity 之和，用于顶部购物车角标） */
    private Integer totalCount;

    /** 已勾选件数（勾选条目 quantity 之和） */
    private Integer selectedCount;

    /** 已勾选条目合计金额 */
    private BigDecimal selectedAmount;
}
