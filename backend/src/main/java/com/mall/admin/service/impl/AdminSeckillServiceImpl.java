package com.mall.admin.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.mall.admin.dto.AdminSeckillActivityAddDTO;
import com.mall.admin.dto.AdminSeckillActivityUpdateDTO;
import com.mall.admin.dto.AdminSeckillProductAddDTO;
import com.mall.admin.service.AdminSeckillService;
import com.mall.common.BizException;
import com.mall.product.entity.PmsProduct;
import com.mall.product.entity.PmsSku;
import com.mall.product.mapper.ProductMapper;
import com.mall.product.mapper.SkuMapper;
import com.mall.seckill.entity.SmsSeckillActivity;
import com.mall.seckill.entity.SmsSeckillProduct;
import com.mall.seckill.mapper.SeckillActivityMapper;
import com.mall.seckill.mapper.SeckillProductMapper;
import com.mall.seckill.service.impl.SeckillServiceImpl;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 管理端秒杀活动配置实现。
 *
 * <p>Redis 预热约定：秒杀下单依赖 Redis 预扣（key {@code mall:seckill:stock:{spId}}，
 * 缺失时 Lua 返回 -3 未预热），启动预热由 {@code SeckillWarmUpRunner} 负责；
 * 管理端「活动上线 / 上线活动中添加商品」时需同步写 Redis，保证配置即可售。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AdminSeckillServiceImpl implements AdminSeckillService {

    private static final int STATUS_ONLINE = 1;
    private static final int DEFAULT_LIMIT_PER_USER = 1;

    private final SeckillActivityMapper activityMapper;
    private final SeckillProductMapper seckillProductMapper;
    private final ProductMapper productMapper;
    private final SkuMapper skuMapper;
    private final StringRedisTemplate stringRedisTemplate;

    @Override
    public void addActivity(AdminSeckillActivityAddDTO dto) {
        requireTimeRange(dto.getStartTime(), dto.getEndTime());
        SmsSeckillActivity activity = new SmsSeckillActivity();
        activity.setName(dto.getName().trim());
        activity.setStartTime(dto.getStartTime());
        activity.setEndTime(dto.getEndTime());
        activity.setStatus(dto.getStatus() != null ? dto.getStatus() : 0);
        activityMapper.insert(activity);
        log.info("管理端新增秒杀活动成功: id={}, name={}, status={}", activity.getId(), activity.getName(), activity.getStatus());
        // 新增即上线：直接预热该活动的秒杀库存
        if (activity.getStatus() == STATUS_ONLINE) {
            warmUpStock(activity.getId());
        }
    }

    @Override
    public void updateActivity(Long id, AdminSeckillActivityUpdateDTO dto) {
        SmsSeckillActivity activity = requireActivity(id);
        if (StringUtils.hasText(dto.getName())) {
            activity.setName(dto.getName().trim());
        }
        if (dto.getStartTime() != null) {
            activity.setStartTime(dto.getStartTime());
        }
        if (dto.getEndTime() != null) {
            activity.setEndTime(dto.getEndTime());
        }
        requireTimeRange(activity.getStartTime(), activity.getEndTime());
        if (dto.getStatus() != null) {
            if (dto.getStatus() != 0 && dto.getStatus() != 1) {
                throw new BizException("非法的活动状态");
            }
            activity.setStatus(dto.getStatus());
        }
        activityMapper.updateById(activity);
        log.info("管理端修改秒杀活动成功: id={}, status={}", id, activity.getStatus());
        // 置为上线时预热（整体覆盖为 DB 当前库存，与 SeckillWarmUpRunner 语义一致）
        if (activity.getStatus() == STATUS_ONLINE) {
            warmUpStock(id);
        }
    }

    @Override
    public void addSeckillProduct(AdminSeckillProductAddDTO dto) {
        SmsSeckillActivity activity = requireActivity(dto.getActivityId());
        PmsProduct product = productMapper.selectById(dto.getProductId());
        if (product == null) {
            throw new BizException("商品不存在");
        }
        PmsSku sku = skuMapper.selectById(dto.getSkuId());
        if (sku == null || !sku.getProductId().equals(dto.getProductId())) {
            throw new BizException("SKU不存在或不属于该商品");
        }
        SmsSeckillProduct sp = new SmsSeckillProduct();
        sp.setActivityId(dto.getActivityId());
        sp.setProductId(dto.getProductId());
        sp.setSkuId(dto.getSkuId());
        sp.setSeckillPrice(dto.getSeckillPrice());
        sp.setSeckillStock(dto.getSeckillStock());
        sp.setLimitPerUser(dto.getLimitPerUser() != null ? dto.getLimitPerUser() : DEFAULT_LIMIT_PER_USER);
        seckillProductMapper.insert(sp);
        log.info("管理端新增秒杀商品成功: id={}, activityId={}, productId={}, skuId={}",
                sp.getId(), dto.getActivityId(), dto.getProductId(), dto.getSkuId());
        // 活动上线中：新配置的秒杀商品立即可抢，需写入 Redis 预热库存
        if (activity.getStatus() == STATUS_ONLINE) {
            stringRedisTemplate.opsForValue().set(
                    SeckillServiceImpl.STOCK_KEY_PREFIX + sp.getId(), String.valueOf(sp.getSeckillStock()));
        }
    }

    @Override
    public void deleteSeckillProduct(Long id) {
        requireSeckillProduct(id);
        seckillProductMapper.deleteById(id);
        // 清理预热库存 key，避免残留旧数据（重复添加同 id 不会发生，自增ID不复用，仅防脏读）
        stringRedisTemplate.delete(SeckillServiceImpl.STOCK_KEY_PREFIX + id);
        log.info("管理端删除秒杀商品成功: id={}", id);
    }

    // ---------------- 内部方法 ----------------

    /** 活动上线中：将该活动全部秒杀商品库存整体覆盖写入 Redis（以 DB 为基准） */
    private void warmUpStock(Long activityId) {
        List<SmsSeckillProduct> products = seckillProductMapper.selectList(
                new LambdaQueryWrapper<SmsSeckillProduct>()
                        .eq(SmsSeckillProduct::getActivityId, activityId));
        products.forEach(sp -> stringRedisTemplate.opsForValue().set(
                SeckillServiceImpl.STOCK_KEY_PREFIX + sp.getId(), String.valueOf(sp.getSeckillStock())));
        log.info("秒杀活动上线预热完成: activityId={}, 商品数={}", activityId, products.size());
    }

    private void requireTimeRange(LocalDateTime startTime, LocalDateTime endTime) {
        if (!endTime.isAfter(startTime)) {
            throw new BizException("活动结束时间必须晚于开始时间");
        }
    }

    private SmsSeckillActivity requireActivity(Long id) {
        SmsSeckillActivity activity = activityMapper.selectById(id);
        if (activity == null) {
            throw new BizException("秒杀活动不存在");
        }
        return activity;
    }

    private SmsSeckillProduct requireSeckillProduct(Long id) {
        SmsSeckillProduct sp = seckillProductMapper.selectById(id);
        if (sp == null) {
            throw new BizException("秒杀商品不存在");
        }
        return sp;
    }
}
