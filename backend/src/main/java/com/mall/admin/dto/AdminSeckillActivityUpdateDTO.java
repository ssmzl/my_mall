package com.mall.admin.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 管理端修改秒杀活动请求（与前端 adminUpdateSeckillActivity 契约对齐，仅更新非空字段）。
 */
@Data
public class AdminSeckillActivityUpdateDTO {

    private String name;

    /** 前端传 "yyyy-MM-dd HH:mm:ss" 格式 */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime startTime;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime endTime;

    /** 状态：0下线 1上线；置为上线时预热秒杀库存到 Redis */
    private Integer status;
}
