package com.mall.admin.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import lombok.Data;

import java.math.BigDecimal;

/**
 * 管理端新增商品请求（与前端 AdminProductPayload 契约对齐）。
 */
@Data
public class AdminProductAddDTO {

    /** 所属分类ID（二级分类） */
    @NotNull(message = "分类不能为空")
    private Long categoryId;

    @NotBlank(message = "商品名称不能为空")
    private String name;

    private String subTitle;

    private String mainPic;

    /** 商品详情（富文本/HTML），可选 */
    private String detail;

    /** 展示价格（元） */
    @NotNull(message = "价格不能为空")
    @DecimalMin(value = "0.01", message = "价格必须大于0")
    private BigDecimal price;

    /** 总库存（非负） */
    @NotNull(message = "库存不能为空")
    @PositiveOrZero(message = "库存不能为负数")
    private Integer stock;
}
