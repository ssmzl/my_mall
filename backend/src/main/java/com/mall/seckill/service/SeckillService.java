package com.mall.seckill.service;

import com.mall.seckill.dto.SeckillActivityVO;
import com.mall.seckill.dto.SeckillOrderMessage;
import com.mall.seckill.dto.SeckillOrderVO;
import com.mall.seckill.dto.SeckillResultVO;

import java.util.List;

/**
 * 秒杀服务。
 */
public interface SeckillService {

    /** 秒杀活动列表（含活动商品，实时剩余库存） */
    List<SeckillActivityVO> listActivities();

    /**
     * 秒杀下单（异步）：简单限流 → Lua 原子预扣库存 + 限购校验 → 写入 QUEUED 任务 → 投递 MQ。
     *
     * @return taskId 供前端轮询结果
     */
    SeckillOrderVO seckillOrder(Long seckillProductId);

    /** 查询秒杀下单结果（前端轮询，QUEUED 继续轮询） */
    SeckillResultVO getResult(String taskId);

    /**
     * 消费端异步建单：DB 条件扣减秒杀库存（最终防线）→ 生成订单/订单项（秒杀价快照）→
     * 事务提交后发送延迟关单消息 → 任务置 SUCCESS。
     */
    void createSeckillOrder(SeckillOrderMessage message);

    /** 建单失败兜底：任务置 FAIL 并归还 Redis 预扣（DB 侧由事务回滚保证） */
    void failTask(SeckillOrderMessage message, String reason);
}
