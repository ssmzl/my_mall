package com.mall.seckill.support;

import org.springframework.data.redis.core.script.DefaultRedisScript;

/**
 * 秒杀 Redis Lua 脚本：原子执行「限购校验 + 库存预扣」。
 *
 * <p>KEYS[1] = 秒杀库存 key（mall:seckill:stock:{seckillProductId}）
 * <br>KEYS[2] = 用户已购计数 key（mall:seckill:purchased:{seckillProductId}:{userId}）
 * <br>ARGV[1] = 每人限购件数
 * <br>ARGV[2] = 已购计数 TTL（秒，取活动结束时间 + 缓冲，防止计数永不过期堆积）
 *
 * <p>返回值：1 成功（库存-1、已购+1）；-1 库存不足（售罄）；-2 超出限购；-3 库存 key 不存在（未预热）。
 * 整段脚本在 Redis 中原子执行，并发下不会超卖、不会突破限购。
 */
public final class SeckillLua {

    public static final DefaultRedisScript<Long> SECKILL_DEDUCT = new DefaultRedisScript<>(

            // 库存 key 不存在：活动未预热或预热数据丢失，拒绝下单（不能按 0 库存处理，避免误判售罄）
            "local stock = redis.call('GET', KEYS[1]) "
            + "if not stock then return -3 end "
            + "if tonumber(stock) < 1 then return -1 end "
            + "local purchased = tonumber(redis.call('GET', KEYS[2]) or '0') "
            + "if purchased >= tonumber(ARGV[1]) then return -2 end "
            + "redis.call('DECR', KEYS[1]) "
            + "if redis.call('INCR', KEYS[2]) == 1 then "
            + "  redis.call('EXPIRE', KEYS[2], tonumber(ARGV[2])) "
            + "end "
            + "return 1",
            Long.class);

    private SeckillLua() {
    }
}
