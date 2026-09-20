package com.mall.product.dto;

import com.mall.product.entity.PmsProduct;
import com.mall.product.entity.PmsSku;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.List;

/**
 * 商品详情 VO：在商品实体基础上附加 SKU 列表（与前端 ProductDetail 契约对齐）。
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class ProductDetailVO extends PmsProduct {

    /** SKU 列表 */
    private List<PmsSku> skus;
}
