package com.mall.admin.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 管理端新增秒杀活动请求（与前端 AdminSeckillActivityPayload 契约对齐）。
 */
@Data
public class AdminSeckillActivityAddDTO {

    @NotBlank(message = "活动名称不能为空")
    private String name;

    /** 前端传 "yyyy-MM-dd HH:mm:ss" 格式 */
    @NotNull(message = "开始时间不能为空")
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime startTime;

    @NotNull(message = "结束时间不能为空")
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime endTime;

    /** 状态：0下线 1上线，缺省下线（上线后需预热秒杀库存到 Redis） */
    private Integer status;
}
