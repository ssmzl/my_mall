package com.mall.seckill.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mall.common.BizException;
import com.mall.order.entity.OmsOrder;
import com.mall.order.entity.OmsOrderItem;
import com.mall.order.mapper.OrderItemMapper;
import com.mall.order.mapper.OrderMapper;
import com.mall.order.mq.OrderCloseProducer;
import com.mall.product.entity.PmsProduct;
import com.mall.product.entity.PmsSku;
import com.mall.product.mapper.ProductMapper;
import com.mall.product.mapper.SkuMapper;
import com.mall.seckill.dto.SeckillActivityVO;
import com.mall.seckill.dto.SeckillOrderMessage;
import com.mall.seckill.dto.SeckillOrderVO;
import com.mall.seckill.dto.SeckillProductVO;
import com.mall.seckill.dto.SeckillResultVO;
import com.mall.seckill.dto.SeckillTask;
import com.mall.seckill.entity.SmsSeckillActivity;
import com.mall.seckill.entity.SmsSeckillProduct;
import com.mall.seckill.mapper.SeckillActivityMapper;
import com.mall.seckill.mapper.SeckillProductMapper;
import com.mall.seckill.mq.SeckillOrderProducer;
import com.mall.seckill.service.SeckillService;
import com.mall.seckill.support.SeckillLua;
import com.mall.security.LoginUser;
import com.mall.user.entity.UmsAddress;
import com.mall.user.mapper.AddressMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 秒杀服务实现。
 *
 * <p>关键设计：
 * <ul>
 *   <li>预热：启动时将进行中/未开始活动的秒杀库存写入 Redis（key {@code mall:seckill:stock:{spId}}），
 *       下单请求全部在 Redis 侧完成预扣，DB 不承接秒杀瞬时流量；</li>
 *   <li>不超卖：Lua 脚本原子执行「限购校验 + 库存预扣」（DECR 前置校验），并发下不会扣成负数；
 *       异步建单时再用 DB 条件扣减（UPDATE ... WHERE seckill_stock >= qty）兜底，Redis 与 DB 双保险；</li>
 *   <li>限购：Lua 脚本内校验并累加用户已购计数（{@code mall:seckill:purchased:{spId}:{userId}}），
 *       TTL 取活动结束时间 + 1 小时，避免计数 key 永久堆积；</li>
 *   <li>削峰：预扣成功仅写入 QUEUED 任务并投递 MQ，消费者异步建单，前端凭 taskId 轮询结果；</li>
 *   <li>一致性：预扣成功但 MQ 发送失败 → 回滚 Redis（库存+1、已购-1）并拒绝本次抢购；
 *       异步建单失败 → DB 事务回滚 + 归还 Redis 预扣 + 任务置 FAIL。</li>
 * </ul>
 */
@Slf4j
@Service
public class SeckillServiceImpl implements SeckillService {

    /** 秒杀库存 Redis key 前缀 */
    public static final String STOCK_KEY_PREFIX = "mall:seckill:stock:";
    /** 用户已购计数 Redis key 前缀 */
    public static final String PURCHASED_KEY_PREFIX = "mall:seckill:purchased:";
    /** 下单任务 Redis key 前缀 */
    public static final String TASK_KEY_PREFIX = "mall:seckill:task:";
    /** 简单限流 Redis key 前缀（固定窗口 1s） */
    public static final String RATE_KEY_PREFIX = "mall:seckill:rate:";

    private static final DateTimeFormatter ORDER_NO_FORMAT = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    /** 商品上架状态 */
    private static final int STATUS_ON = 1;
    /** 活动上线状态 */
    private static final int ACTIVITY_ON = 1;

    private final SeckillActivityMapper activityMapper;
    private final SeckillProductMapper seckillProductMapper;
    private final ProductMapper productMapper;
    private final SkuMapper skuMapper;
    private final AddressMapper addressMapper;
    private final OrderMapper orderMapper;
    private final OrderItemMapper orderItemMapper;
    private final OrderCloseProducer orderCloseProducer;
    private final SeckillOrderProducer seckillOrderProducer;
    private final StringRedisTemplate stringRedisTemplate;
    private final ObjectMapper objectMapper;

    /** 每用户每秒最大抢购请求数（简单固定窗口限流） */
    private final int rateLimitPerSecond;
    /** 任务 Redis TTL（分钟），超时未消费视为失败 */
    private final long taskTtlMinutes;

    public SeckillServiceImpl(SeckillActivityMapper activityMapper, SeckillProductMapper seckillProductMapper,
                              ProductMapper productMapper, SkuMapper skuMapper, AddressMapper addressMapper,
                              OrderMapper orderMapper, OrderItemMapper orderItemMapper,
                              OrderCloseProducer orderCloseProducer, SeckillOrderProducer seckillOrderProducer,
                              StringRedisTemplate stringRedisTemplate, ObjectMapper objectMapper,
                              @Value("${mall.seckill.rate-limit-per-second:3}") int rateLimitPerSecond,
                              @Value("${mall.seckill.task-ttl-minutes:30}") long taskTtlMinutes) {
        this.activityMapper = activityMapper;
        this.seckillProductMapper = seckillProductMapper;
        this.productMapper = productMapper;
        this.skuMapper = skuMapper;
        this.addressMapper = addressMapper;
        this.orderMapper = orderMapper;
        this.orderItemMapper = orderItemMapper;
        this.orderCloseProducer = orderCloseProducer;
        this.seckillOrderProducer = seckillOrderProducer;
        this.stringRedisTemplate = stringRedisTemplate;
        this.objectMapper = objectMapper;
        this.rateLimitPerSecond = rateLimitPerSecond;
        this.taskTtlMinutes = taskTtlMinutes;
    }

    @Override
    public List<SeckillActivityVO> listActivities() {
        LocalDateTime now = LocalDateTime.now();
        // 仅上线且未结束的活动；未开始的也返回（前端展示倒计时）
        List<SmsSeckillActivity> activities = activityMapper.selectList(new LambdaQueryWrapper<SmsSeckillActivity>()
                .eq(SmsSeckillActivity::getStatus, ACTIVITY_ON)
                .gt(SmsSeckillActivity::getEndTime, now)
                .orderByAsc(SmsSeckillActivity::getStartTime)
                .orderByAsc(SmsSeckillActivity::getId));
        if (activities.isEmpty()) {
            return List.of();
        }

        List<SmsSeckillProduct> products = seckillProductMapper.selectList(
                new LambdaQueryWrapper<SmsSeckillProduct>()
                        .in(SmsSeckillProduct::getActivityId,
                                activities.stream().map(SmsSeckillActivity::getId).toList())
                        .orderByAsc(SmsSeckillProduct::getId));
        // 批量查商品（名称/主图/原价），秒杀商品数有限无需分页
        Map<Long, PmsProduct> productMap = products.isEmpty() ? Map.of()
                : productMapper.selectList(new LambdaQueryWrapper<PmsProduct>()
                        .in(PmsProduct::getId, products.stream().map(SmsSeckillProduct::getProductId).toList()))
                .stream().collect(Collectors.toMap(PmsProduct::getId, Function.identity(), (a, b) -> a, LinkedHashMap::new));

        Map<Long, List<SeckillProductVO>> productsByActivity = products.stream()
                .map(sp -> toProductVO(sp, productMap.get(sp.getProductId())))
                .collect(Collectors.groupingBy(SeckillProductVO::getActivityId, LinkedHashMap::new, Collectors.toList()));

        return activities.stream().map(activity -> {
            SeckillActivityVO vo = new SeckillActivityVO();
            vo.setId(activity.getId());
            vo.setName(activity.getName());
            vo.setStartTime(activity.getStartTime());
            vo.setEndTime(activity.getEndTime());
            vo.setStatus(activity.getStatus());
            vo.setProducts(productsByActivity.getOrDefault(activity.getId(), List.of()));
            return vo;
        }).collect(Collectors.toList());
    }

    @Override
    public SeckillOrderVO seckillOrder(Long seckillProductId) {
        Long userId = currentUserId();

        // 1. 简单限流：固定窗口 1s，超出阈值直接拒绝（拦截刷接口）
        checkRateLimit(userId);

        // 2. 校验秒杀商品与活动状态
        SmsSeckillProduct sp = seckillProductMapper.selectById(seckillProductId);
        if (sp == null) {
            throw new BizException("秒杀商品不存在");
        }
        SmsSeckillActivity activity = activityMapper.selectById(sp.getActivityId());
        LocalDateTime now = LocalDateTime.now();
        if (activity == null || !Integer.valueOf(ACTIVITY_ON).equals(activity.getStatus())
                || now.isBefore(activity.getStartTime()) || now.isAfter(activity.getEndTime())) {
            throw new BizException("活动未开始或已结束");
        }
        PmsProduct product = productMapper.selectById(sp.getProductId());
        if (product == null || !Integer.valueOf(STATUS_ON).equals(product.getStatus())) {
            throw new BizException("商品已下架或不存在");
        }

        // 3. Lua 原子预扣：限购校验 + 库存-1 + 已购+1
        String stockKey = stockKey(seckillProductId);
        String purchasedKey = purchasedKey(seckillProductId, userId);
        long purchasedTtlSeconds = Math.max(Duration.between(now, activity.getEndTime()).plusHours(1).getSeconds(), 60);
        Long result = stringRedisTemplate.execute(SeckillLua.SECKILL_DEDUCT,
                List.of(stockKey, purchasedKey),
                String.valueOf(sp.getLimitPerUser()), String.valueOf(purchasedTtlSeconds));
        if (result == null) {
            log.error("秒杀 Lua 预扣返回 null: seckillProductId={}, userId={}", seckillProductId, userId);
            throw new BizException("系统繁忙，请稍后重试");
        }
        if (result == -1) {
            throw new BizException("手慢了，商品已被抢光");
        }
        if (result == -2) {
            throw new BizException("已达到每人限购数量");
        }
        if (result != 1) {
            // -3：库存 key 不存在（未预热），诊断信息输出便于排查
            log.error("秒杀预扣失败（库存key不存在，未预热或数据丢失）: seckillProductId={}, userId={}", seckillProductId, userId);
            throw new BizException("活动太火爆，请稍后重试");
        }

        // 4. 写入 QUEUED 任务并投递 MQ；发送失败回滚 Redis 预扣
        String taskId = UUID.randomUUID().toString().replace("-", "");
        SeckillTask task = new SeckillTask();
        task.setUserId(userId);
        task.setSeckillProductId(seckillProductId);
        task.setStatus(SeckillTask.STATUS_QUEUED);
        saveTask(taskId, task);

        try {
            seckillOrderProducer.sendSeckillOrder(taskId, userId, seckillProductId);
        } catch (Exception e) {
            rollbackReserve(stockKey, purchasedKey);
            stringRedisTemplate.delete(taskKey(taskId));
            throw e;
        }

        log.info("秒杀预扣成功，已投递异步建单: userId={}, seckillProductId={}, taskId={}",
                userId, seckillProductId, taskId);
        return new SeckillOrderVO(taskId);
    }

    @Override
    public SeckillResultVO getResult(String taskId) {
        Long userId = currentUserId();
        SeckillTask task = readTask(taskId);
        // 任务不存在/已过期/他人任务：一律按失败结果返回，不暴露任务是否存在
        if (task == null || !task.getUserId().equals(userId)) {
            return new SeckillResultVO(SeckillTask.STATUS_FAIL, "任务不存在或已过期", null);
        }
        return new SeckillResultVO(task.getStatus(), task.getMessage(), task.getOrderNo());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void createSeckillOrder(SeckillOrderMessage message) {
        String taskId = message.getTaskId();
        Long spId = message.getSeckillProductId();

        // 1. 任务校验（幂等）：不存在（残留消息）或非 QUEUED（已处理/重复投递）直接跳过
        SeckillTask task = readTask(taskId);
        if (task == null) {
            log.warn("秒杀任务不存在，跳过建单（可能任务已过期或 Redis 数据丢失）: taskId={}", taskId);
            return;
        }
        if (!SeckillTask.STATUS_QUEUED.equals(task.getStatus())) {
            log.info("秒杀任务已处理，跳过重复建单: taskId={}, status={}", taskId, task.getStatus());
            return;
        }
        Long userId = task.getUserId();

        // 2. DB 条件扣减秒杀库存（Redis 预扣之后的最终防线，防极端场景超卖）
        SmsSeckillProduct sp = seckillProductMapper.selectById(spId);
        if (sp == null) {
            completeFail(taskId, spId, userId, "秒杀商品不存在");
            return;
        }
        int deducted = seckillProductMapper.deductSeckillStock(spId, 1);
        if (deducted == 0) {
            completeFail(taskId, spId, userId, "手慢了，商品已被抢光");
            return;
        }

        // 3. 商品/SKU 校验（异常走事务回滚 + failTask 归还）
        PmsProduct product = productMapper.selectById(sp.getProductId());
        PmsSku sku = skuMapper.selectById(sp.getSkuId());
        if (product == null || sku == null) {
            seckillProductMapper.restockSeckillStock(spId, 1);
            completeFail(taskId, spId, userId, "商品信息异常，抢购失败");
            return;
        }

        // 4. 生成订单/订单项（秒杀价快照；收货地址取用户默认地址，可空）
        OmsOrder order = new OmsOrder();
        order.setOrderNo(generateOrderNo());
        order.setUserId(userId);
        order.setTotalAmount(sp.getSeckillPrice());
        order.setStatus(0);
        order.setAddressSnapshot(buildDefaultAddressSnapshot(userId));
        orderMapper.insert(order);

        OmsOrderItem item = new OmsOrderItem();
        item.setOrderId(order.getId());
        item.setProductId(sp.getProductId());
        item.setSkuId(sp.getSkuId());
        item.setProductName(product.getName());
        item.setSkuSpec(toSpecText(sku));
        item.setPrice(sp.getSeckillPrice());
        item.setQuantity(1);
        orderItemMapper.insert(item);

        // 5. 事务提交后发送延迟关单消息（秒杀单同样超时未支付自动关单）
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                orderCloseProducer.sendDelayClose(order.getOrderNo());
            }
        });

        // 6. 任务置 SUCCESS（Redis 写入在事务内但不回滚 DB；失败时 DB 已回滚、
        //    任务停留 QUEUED 由消费者异常兜底置 FAIL，见 SeckillOrderConsumer）
        task.setStatus(SeckillTask.STATUS_SUCCESS);
        task.setOrderNo(order.getOrderNo());
        saveTask(taskId, task);
        log.info("秒杀异步建单成功: taskId={}, userId={}, orderNo={}, seckillProductId={}",
                taskId, userId, order.getOrderNo(), spId);
    }

    @Override
    public void failTask(SeckillOrderMessage message, String reason) {
        completeFail(message.getTaskId(), message.getSeckillProductId(), message.getUserId(), reason);
    }

    // ---------------- 内部方法 ----------------

    /** 建单失败统一处理：DB 事务回滚（或未扣减）后归还 Redis 预扣 + 任务置 FAIL */
    private void completeFail(String taskId, Long spId, Long userId, String reason) {
        String stockKey = stockKey(spId);
        String purchasedKey = purchasedKey(spId, userId);
        stringRedisTemplate.opsForValue().increment(stockKey);
        decrementPurchased(purchasedKey);

        SeckillTask task = readTask(taskId);
        if (task != null) {
            task.setStatus(SeckillTask.STATUS_FAIL);
            task.setMessage(reason);
            task.setOrderNo(null);
            saveTask(taskId, task);
        }
        log.warn("秒杀建单失败，已归还 Redis 预扣: taskId={}, spId={}, userId={}, reason={}", taskId, spId, userId, reason);
    }

    /** 预扣后 MQ 发送失败回滚：库存+1、已购-1 */
    private void rollbackReserve(String stockKey, String purchasedKey) {
        stringRedisTemplate.opsForValue().increment(stockKey);
        decrementPurchased(purchasedKey);
    }

    /** 已购计数-1（下限 0，防止误删/重试导致负数） */
    private void decrementPurchased(String purchasedKey) {
        String value = stringRedisTemplate.opsForValue().get(purchasedKey);
        long current = value == null ? 0 : Long.parseLong(value);
        if (current > 0) {
            stringRedisTemplate.opsForValue().set(purchasedKey, String.valueOf(current - 1),
                    Duration.ofHours(1));
        }
    }

    /** 简单限流：固定窗口 1s 计数，首个请求负责写入过期时间 */
    private void checkRateLimit(Long userId) {
        String rateKey = RATE_KEY_PREFIX + userId;
        Long count = stringRedisTemplate.opsForValue().increment(rateKey);
        if (count == null) {
            return;
        }
        if (count == 1) {
            stringRedisTemplate.expire(rateKey, Duration.ofSeconds(1));
        }
        if (count > rateLimitPerSecond) {
            log.warn("秒杀请求触发限流: userId={}, qps={}（阈值 {}）", userId, count, rateLimitPerSecond);
            throw new BizException("操作过于频繁，请稍后再试");
        }
    }

    private void saveTask(String taskId, SeckillTask task) {
        try {
            stringRedisTemplate.opsForValue().set(taskKey(taskId), objectMapper.writeValueAsString(task),
                    Duration.ofMinutes(taskTtlMinutes));
        } catch (JsonProcessingException e) {
            log.error("秒杀任务序列化失败: taskId={}", taskId, e);
            throw new BizException("系统繁忙，请稍后重试");
        }
    }

    private SeckillTask readTask(String taskId) {
        String json = stringRedisTemplate.opsForValue().get(taskKey(taskId));
        if (json == null) {
            return null;
        }
        try {
            return objectMapper.readValue(json, SeckillTask.class);
        } catch (JsonProcessingException e) {
            log.warn("秒杀任务JSON解析失败，按不存在处理: taskId={}, json={}", taskId, json, e);
            return null;
        }
    }

    /** 活动商品 VO：实时库存优先取 Redis 预扣计数，未预热/丢失回退 DB 值 */
    private SeckillProductVO toProductVO(SmsSeckillProduct sp, PmsProduct product) {
        SeckillProductVO vo = new SeckillProductVO();
        vo.setId(sp.getId());
        vo.setActivityId(sp.getActivityId());
        vo.setProductId(sp.getProductId());
        vo.setSkuId(sp.getSkuId());
        vo.setProductName(product != null ? product.getName() : "");
        vo.setMainPic(product != null ? product.getMainPic() : null);
        vo.setSeckillPrice(sp.getSeckillPrice());
        vo.setOriginalPrice(product != null ? product.getPrice() : sp.getSeckillPrice());
        vo.setLimitPerUser(sp.getLimitPerUser());

        Integer redisStock = null;
        try {
            String stock = stringRedisTemplate.opsForValue().get(stockKey(sp.getId()));
            if (stock != null) {
                redisStock = Integer.parseInt(stock);
            }
        } catch (Exception e) {
            log.warn("读取秒杀 Redis 库存失败，回退 DB 值: seckillProductId={}", sp.getId(), e);
        }
        vo.setSeckillStock(redisStock != null ? redisStock : sp.getSeckillStock());
        return vo;
    }

    /** 默认地址快照 JSON（无默认地址返回 null，与 oms_order.address_snapshot 可空一致） */
    private String buildDefaultAddressSnapshot(Long userId) {
        UmsAddress address = addressMapper.selectOne(new LambdaQueryWrapper<UmsAddress>()
                .eq(UmsAddress::getUserId, userId)
                .eq(UmsAddress::getIsDefault, 1)
                .last("LIMIT 1"));
        if (address == null) {
            return null;
        }
        try {
            Map<String, String> snapshot = new LinkedHashMap<>();
            snapshot.put("receiverName", address.getReceiverName());
            snapshot.put("receiverPhone", address.getReceiverPhone());
            snapshot.put("province", address.getProvince());
            snapshot.put("city", address.getCity());
            snapshot.put("district", address.getDistrict());
            snapshot.put("detailAddress", address.getDetailAddress());
            return objectMapper.writeValueAsString(snapshot);
        } catch (JsonProcessingException e) {
            log.error("默认地址快照序列化失败: userId={}, addressId={}", userId, address.getId(), e);
            // 快照失败不阻断建单：订单可正常支付，仅缺地址展示
            return null;
        }
    }

    /** SKU 规格解析为展示文案（值以 " / " 拼接），无规格/损坏回退 skuCode */
    private String toSpecText(PmsSku sku) {
        if (sku.getSpecJson() == null || sku.getSpecJson().isBlank()) {
            return sku.getSkuCode();
        }
        try {
            Map<String, Object> spec = objectMapper.readValue(sku.getSpecJson(),
                    new TypeReference<Map<String, Object>>() { });
            if (spec.isEmpty()) {
                return sku.getSkuCode();
            }
            return spec.values().stream().map(String::valueOf).collect(Collectors.joining(" / "));
        } catch (JsonProcessingException e) {
            log.warn("SKU 规格JSON解析失败，回退skuCode: skuId={}, specJson={}", sku.getId(), sku.getSpecJson());
            return sku.getSkuCode();
        }
    }

    /** 订单编号：时间戳 + 6位随机数（唯一索引兜底，与普通下单规则一致） */
    private String generateOrderNo() {
        return ORDER_NO_FORMAT.format(LocalDateTime.now())
                + ThreadLocalRandom.current().nextInt(100000, 1000000);
    }

    private String stockKey(Long seckillProductId) {
        return STOCK_KEY_PREFIX + seckillProductId;
    }

    private String purchasedKey(Long seckillProductId, Long userId) {
        return PURCHASED_KEY_PREFIX + seckillProductId + ":" + userId;
    }

    private String taskKey(String taskId) {
        return TASK_KEY_PREFIX + taskId;
    }

    private Long currentUserId() {
        Long userId = LoginUser.getCurrentUserId();
        if (userId == null) {
            throw new BizException("未登录");
        }
        return userId;
    }
}
