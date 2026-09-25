package com.mall.seckill.dto;

import lombok.Data;

import java.io.Serializable;

/**
 * 秒杀下单任务（Redis 存储的任务状态，key: mall:seckill:task:{taskId}，JSON 序列化）。
 * 消费端异步建单成功/失败后更新状态，前端凭 taskId 轮询读取。
 */
@Data
public class SeckillTask implements Serializable {

    public static final String STATUS_QUEUED = "QUEUED";
    public static final String STATUS_SUCCESS = "SUCCESS";
    public static final String STATUS_FAIL = "FAIL";

    /** 下单用户（结果查询时校验归属，防止越权探测他人任务） */
    private Long userId;

    /** 秒杀商品ID */
    private Long seckillProductId;

    /** 任务状态：QUEUED / SUCCESS / FAIL */
    private String status;

    /** 失败原因/提示信息 */
    private String message;

    /** 抢购成功时的订单编号 */
    private String orderNo;
}
