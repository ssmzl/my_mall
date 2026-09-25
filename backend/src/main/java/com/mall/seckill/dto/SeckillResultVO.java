package com.mall.seckill.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 秒杀下单结果（前端轮询，与前端 SeckillResultVO 契约一致）。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class SeckillResultVO implements Serializable {

    /** QUEUED 排队中 / SUCCESS 成功 / FAIL 失败 */
    private String status;

    private String message;

    /** 抢购成功时的订单编号 */
    private String orderNo;
}
