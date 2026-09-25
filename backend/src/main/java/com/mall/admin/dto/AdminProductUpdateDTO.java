package com.mall.admin.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.PositiveOrZero;
import lombok.Data;

import java.math.BigDecimal;

/**
 * 管理端修改商品请求（与前端 adminUpdateProduct 契约对齐，仅更新非空字段）。
 */
@Data
public class AdminProductUpdateDTO {

    private Long categoryId;

    private String name;

    private String subTitle;

    private String mainPic;

    private String detail;

    @DecimalMin(value = "0.01", message = "价格必须大于0")
    private BigDecimal price;

    @PositiveOrZero(message = "库存不能为负数")
    private Integer stock;
}
