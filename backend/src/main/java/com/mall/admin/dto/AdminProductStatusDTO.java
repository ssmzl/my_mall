package com.mall.admin.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 管理端商品上架/下架请求（与前端 adminUpdateProductStatus 契约对齐）。
 */
@Data
public class AdminProductStatusDTO {

    /** 状态：0下架 1上架 */
    @NotNull(message = "状态不能为空")
    private Integer status;
}
