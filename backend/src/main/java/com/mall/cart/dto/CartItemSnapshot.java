package com.mall.cart.dto;

import lombok.Data;

import java.math.BigDecimal;

/**
 * 购物车条目快照（Redis Hash 中的 value，JSON 序列化存储）。
 *
 * <p>key: mall:cart:{userId}，field: skuId，value: 本快照 JSON。
 * 加购时固化商品名/规格/主图/价格，避免展示时反查商品库。
 */
@Data
public class CartItemSnapshot {

    private Long skuId;

    private Long productId;

    private String productName;

    /** 规格展示文案（由 specJson 解析，值为 " / " 拼接；无规格时为 skuCode） */
    private String skuSpec;

    private String mainPic;

    /** 加购时的 SKU 单价（元） */
    private BigDecimal price;

    private Integer quantity;

    /** 是否勾选结算 */
    private Boolean selected;

    /** 加购时间戳（毫秒），用于排序（先加入的排前） */
    private Long addTime;
}
