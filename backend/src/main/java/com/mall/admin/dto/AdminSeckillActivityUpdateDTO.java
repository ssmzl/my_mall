package com.mall.admin.dto;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 管理端修改秒杀活动请求（与前端 adminUpdateSeckillActivity 契约对齐，仅更新非空字段）。
 */
@Data
public class AdminSeckillActivityUpdateDTO {

    private String name;

    private LocalDateTime startTime;

    private LocalDateTime endTime;

    /** 状态：0下线 1上线；置为上线时预热秒杀库存到 Redis */
    private Integer status;
}
