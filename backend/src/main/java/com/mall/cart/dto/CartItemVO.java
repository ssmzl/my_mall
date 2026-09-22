package com.mall.cart.dto;

import lombok.Data;

import java.math.BigDecimal;

/**
 * 购物车条目视图，与前端 CartItem 契约对齐。
 */
@Data
public class CartItemVO {

    private Long skuId;

    private Long productId;

    private String productName;

    private String skuSpec;

    private String mainPic;

    private BigDecimal price;

    private Integer quantity;

    private Boolean selected;

    /** 小计 = price * quantity */
    private BigDecimal subtotal;
}
