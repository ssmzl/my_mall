package com.mall.seckill.config;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.mall.seckill.entity.SmsSeckillActivity;
import com.mall.seckill.entity.SmsSeckillProduct;
import com.mall.seckill.mapper.SeckillActivityMapper;
import com.mall.seckill.mapper.SeckillProductMapper;
import com.mall.seckill.service.impl.SeckillServiceImpl;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 秒杀库存预热：应用启动时将「上线且未结束」活动的秒杀商品库存加载到 Redis
 * （key {@code mall:seckill:stock:{spId}}，值为 DB 当前秒杀库存）。
 *
 * <p>预热以 DB 为基准整体覆盖（SET），适用于应用启动/重启场景：重启时若有进行中活动，
 * DB 库存尚未被异步建单扣减完的部分即为准（Redis 预扣只在活动进行中生效，重启丢失的
 * 预扣计数以 DB 回补后的值为准，宁可少量超卖风险由 DB 条件扣减兜底，也不允许少卖）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SeckillWarmUpRunner implements ApplicationRunner {

    private final SeckillActivityMapper activityMapper;
    private final SeckillProductMapper seckillProductMapper;
    private final StringRedisTemplate stringRedisTemplate;

    @Override
    public void run(ApplicationArguments args) {
        try {
            List<SmsSeckillActivity> activities = activityMapper.selectList(
                    new LambdaQueryWrapper<SmsSeckillActivity>()
                            .eq(SmsSeckillActivity::getStatus, 1)
                            .gt(SmsSeckillActivity::getEndTime, LocalDateTime.now()));
            if (activities.isEmpty()) {
                log.info("秒杀库存预热完成：无进行中/未开始的活动");
                return;
            }
            List<SmsSeckillProduct> products = seckillProductMapper.selectList(
                    new LambdaQueryWrapper<SmsSeckillProduct>()
                            .in(SmsSeckillProduct::getActivityId,
                                    activities.stream().map(SmsSeckillActivity::getId).toList()));
            products.forEach(sp -> stringRedisTemplate.opsForValue().set(
                    SeckillServiceImpl.STOCK_KEY_PREFIX + sp.getId(), String.valueOf(sp.getSeckillStock())));
            log.info("秒杀库存预热完成：活动 {} 个，商品 {} 个已加载到 Redis",
                    activities.size(), products.size());
        } catch (Exception e) {
            // 预热失败不阻断应用启动，但输出完整诊断；未预热的秒杀商品在预扣时会被 Lua 拒绝（返回 -3）
            log.error("秒杀库存预热失败，请检查 MySQL/Redis 连接后重启应用", e);
        }
    }
}
