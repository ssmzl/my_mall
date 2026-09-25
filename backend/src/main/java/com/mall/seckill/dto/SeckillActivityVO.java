package com.mall.seckill.dto;

import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 秒杀活动 VO（含活动商品，与前端 SeckillActivity 契约一致）。
 */
@Data
public class SeckillActivityVO implements Serializable {

    private Long id;

    private String name;

    private LocalDateTime startTime;

    private LocalDateTime endTime;

    /** 状态：0 下线 1 上线 */
    private Integer status;

    /** 活动商品列表 */
    private List<SeckillProductVO> products;
}
