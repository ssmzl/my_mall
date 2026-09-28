package com.mall.seckill;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.mall.order.entity.OmsOrder;
import com.mall.order.entity.OmsOrderItem;
import com.mall.order.mapper.OrderItemMapper;
import com.mall.order.mapper.OrderMapper;
import com.mall.search.support.MiddlewareConditions;
import com.mall.seckill.dto.SeckillOrderVO;
import com.mall.seckill.dto.SeckillResultVO;
import com.mall.seckill.dto.SeckillTask;
import com.mall.seckill.entity.SmsSeckillActivity;
import com.mall.seckill.entity.SmsSeckillProduct;
import com.mall.seckill.mapper.SeckillActivityMapper;
import com.mall.seckill.mapper.SeckillProductMapper;
import com.mall.seckill.service.SeckillService;
import com.mall.seckill.service.impl.SeckillServiceImpl;
import com.mall.security.LoginUser;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.TestPropertySource;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 秒杀防超卖大并发压力测试（连接 docker MySQL / Redis / RocketMQ）。
 *
 * <p>与 {@link SeckillIntegrationTest} 互补：将规模从「20 人抢 5 件」放大到
 * 「500 人抢 100 件」，验证高并发下 Redis Lua 预扣 + DB 条件扣减双防线的超卖不变量：
 * <ul>
 *   <li>预扣成功数 == 库存数（不多不少）；失败数 == 人数 - 库存；</li>
 *   <li>Redis 预扣库存归零、每个成功用户已购计数恰为 1（限购不被并发击穿）；</li>
 *   <li>MQ 异步建单完成后：订单数 == 成功数、下单用户互不重复、DB 秒杀库存扣减为 0（无负数）。</li>
 * </ul>
 *
 * <p>使用独立 ID 段（活动 998001 / 秒杀商品 998002 / 用户 998xxx，避开种子数据、
 * SeckillIntegrationTest 的 990xxx 段与手工演示数据），中间件未启动自动跳过。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@TestPropertySource(properties = {
        "mall.seckill.order-topic=mall-seckill-order-it",
        "mall.seckill.order-consumer-group=mall-seckill-order-consumer-it",
        "mall.seckill.rate-limit-per-second=1000"
})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@EnabledIf(value = "com.mall.search.support.MiddlewareConditions#seckillEnvAvailable",
        disabledReason = "MySQL/Redis/RocketMQ 未启动，跳过秒杀压力测试")
class SeckillOversellStressTest {

    /** 测试数据 ID 段（避开种子数据、其他集成测试的 990xxx 段与手工演示数据，用 998xxx） */
    private static final long ACTIVITY_ID = 998001L;
    private static final long SP_ID = 998002L;
    private static final long USER_BASE = 998101L;
    private static final int USERS = 500;
    private static final int STOCK = 100;
    private static final int LIMIT_PER_USER = 1;

    @Autowired
    private SeckillService seckillService;
    @Autowired
    private SeckillActivityMapper activityMapper;
    @Autowired
    private SeckillProductMapper seckillProductMapper;
    @Autowired
    private OrderMapper orderMapper;
    @Autowired
    private OrderItemMapper orderItemMapper;
    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    /** 抢购成功 taskId → userId（跨测试方法保留） */
    private final Map<String, Long> successTaskUsers = new ConcurrentHashMap<>();

    @BeforeAll
    void prepareTestData() {
        cleanTestData();

        SmsSeckillActivity activity = new SmsSeckillActivity();
        activity.setId(ACTIVITY_ID);
        activity.setName("秒杀防超卖压力测试活动");
        activity.setStartTime(LocalDateTime.now().minusMinutes(5));
        activity.setEndTime(LocalDateTime.now().plusHours(1));
        activity.setStatus(1);
        activityMapper.insert(activity);

        SmsSeckillProduct sp = new SmsSeckillProduct();
        sp.setId(SP_ID);
        sp.setActivityId(ACTIVITY_ID);
        sp.setProductId(1L);
        sp.setSkuId(1L);
        sp.setSeckillPrice(new BigDecimal("1.00"));
        sp.setSeckillStock(STOCK);
        sp.setLimitPerUser(LIMIT_PER_USER);
        seckillProductMapper.insert(sp);

        // 模拟启动预热：秒杀库存写入 Redis（线上由 SeckillWarmUpRunner / 管理端上线预热完成）
        stringRedisTemplate.opsForValue().set(SeckillServiceImpl.STOCK_KEY_PREFIX + SP_ID, String.valueOf(STOCK));
    }

    @AfterAll
    void cleanTestData() {
        List<Long> testUserIds = new ArrayList<>();
        for (int i = 0; i < USERS; i++) {
            testUserIds.add(USER_BASE + i);
        }
        // 订单/订单项按测试用户段清理
        List<OmsOrder> orders = orderMapper.selectList(new LambdaQueryWrapper<OmsOrder>()
                .in(OmsOrder::getUserId, testUserIds));
        if (!orders.isEmpty()) {
            orderItemMapper.delete(new LambdaQueryWrapper<OmsOrderItem>()
                    .in(OmsOrderItem::getOrderId, orders.stream().map(OmsOrder::getId).toList()));
            orderMapper.delete(new LambdaQueryWrapper<OmsOrder>()
                    .in(OmsOrder::getId, orders.stream().map(OmsOrder::getId).toList()));
        }
        seckillProductMapper.deleteById(SP_ID);
        activityMapper.deleteById(ACTIVITY_ID);

        List<String> redisKeys = new ArrayList<>();
        redisKeys.add(SeckillServiceImpl.STOCK_KEY_PREFIX + SP_ID);
        for (long userId : testUserIds) {
            redisKeys.add(SeckillServiceImpl.PURCHASED_KEY_PREFIX + SP_ID + ":" + userId);
            redisKeys.add(SeckillServiceImpl.RATE_KEY_PREFIX + userId);
        }
        stringRedisTemplate.delete(redisKeys);
    }

    @Test
    @Order(1)
    void fiveHundredUsersOnHundredStockShouldNotOversell() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(USERS);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger success = new AtomicInteger();
        AtomicInteger fail = new AtomicInteger();
        long begin = System.nanoTime();
        for (int i = 0; i < USERS; i++) {
            final long userId = USER_BASE + i;
            pool.submit(() -> {
                try {
                    start.await();
                    LoginUser.set(new LoginUser(userId, "stress-user-" + userId, 0));
                    SeckillOrderVO vo = seckillService.seckillOrder(SP_ID);
                    success.incrementAndGet();
                    successTaskUsers.put(vo.getTaskId(), userId);
                } catch (Exception e) {
                    fail.incrementAndGet();
                } finally {
                    LoginUser.remove();
                }
            });
        }
        start.countDown();
        pool.shutdown();
        assertThat(pool.awaitTermination(120, TimeUnit.SECONDS)).isTrue();
        long costMs = (System.nanoTime() - begin) / 1_000_000;

        System.out.printf("[压测] %d 用户并发抢 %d 件：预扣成功 %d，失败 %d，耗时 %d ms（约 %d 笔/s，服务层直调口径）%n",
                USERS, STOCK, success.get(), fail.get(), costMs, USERS * 1000L / Math.max(costMs, 1));

        // 超卖不变量：成功数 == 库存，失败数 == 人数 - 库存
        assertThat(success.get()).as("预扣成功数必须恰好等于库存数").isEqualTo(STOCK);
        assertThat(fail.get()).as("其余请求必须全部失败").isEqualTo(USERS - STOCK);

        // Redis 防线：预扣库存归零，不出现负数
        assertThat(stringRedisTemplate.opsForValue().get(SeckillServiceImpl.STOCK_KEY_PREFIX + SP_ID))
                .as("Redis 预扣库存应归零").isEqualTo("0");

        // 限购不被并发击穿：每个成功用户已购计数恰为 1
        for (Map.Entry<String, Long> entry : successTaskUsers.entrySet()) {
            assertThat(stringRedisTemplate.opsForValue()
                    .get(SeckillServiceImpl.PURCHASED_KEY_PREFIX + SP_ID + ":" + entry.getValue()))
                    .as("用户 %s 已购计数", entry.getValue()).isEqualTo("1");
        }

        // 注意：不在此处断言「DB 库存未被扣减」——测试 JVM 内 MQ 消费者异步建单随时可能
        // 发生，该断言存在固有竞态；DB 终态（库存扣减为 0、订单数一致）由 Order(2) 用例在
        // 等待全部任务终态后校验。
    }

    @Test
    @Order(2)
    void consumedOrdersMustMatchReservationsExactly() {
        // 真实 MQ 消费异步建单：所有预扣任务最终 SUCCESS 且带订单号
        Awaitility.await().atMost(Duration.ofSeconds(120)).pollInterval(Duration.ofSeconds(1)).untilAsserted(() -> {
            assertThat(successTaskUsers).hasSize(STOCK);
            for (Map.Entry<String, Long> entry : successTaskUsers.entrySet()) {
                LoginUser.set(new LoginUser(entry.getValue(), "stress-user-" + entry.getValue(), 0));
                try {
                    SeckillResultVO result = seckillService.getResult(entry.getKey());
                    assertThat(result.getStatus())
                            .as("task %s -> %s", entry.getKey(), result.getStatus())
                            .isEqualTo(SeckillTask.STATUS_SUCCESS);
                    assertThat(result.getOrderNo()).isNotBlank();
                } finally {
                    LoginUser.remove();
                }
            }
        });

        // 订单数 == 预扣成功数，下单用户互不重复（限购在异步建单侧同样成立）
        List<OmsOrder> orders = orderMapper.selectList(new LambdaQueryWrapper<OmsOrder>()
                .in(OmsOrder::getUserId, List.copyOf(successTaskUsers.values())));
        assertThat(orders).as("订单数应等于预扣成功数").hasSize(successTaskUsers.size());
        assertThat(orders.stream().map(OmsOrder::getUserId).distinct().count())
                .as("下单用户数应等于订单数（一人一单）").isEqualTo(successTaskUsers.size());

        // DB 最终防线：条件扣减后秒杀库存为 0，且从未扣成负数（INT UNSIGNED 非负约束 + 断言双保险）
        assertThat(seckillProductMapper.selectById(SP_ID).getSeckillStock())
                .as("DB 秒杀库存应扣减为 0").isZero();
    }
}
