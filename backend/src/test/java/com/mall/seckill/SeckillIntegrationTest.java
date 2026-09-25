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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 秒杀并发集成测试（连接 docker MySQL / Redis / RocketMQ）。
 *
 * <p>使用独立的测试数据 ID 段（990xxx）与独立 Topic/消费组（*_it 后缀）隔离：
 * 20 个用户并发抢 5 件库存验证不超卖；单用户 10 并发抢限购 1 件验证限购；
 * 真实 MQ 链路消费后异步建单，订单数与抢购成功数一致。中间件未启动自动跳过。
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
        disabledReason = "MySQL/Redis/RocketMQ 未启动，跳过秒杀集成测试")
class SeckillIntegrationTest {

    /** 测试数据 ID 段（避开种子数据） */
    private static final long ACTIVITY_ID = 990001L;
    private static final long SP_A = 990001L;
    private static final long SP_B = 990002L;
    private static final long USER_BASE_A = 990101L;
    private static final long USER_B = 990201L;
    private static final int USERS_A = 20;
    private static final int STOCK_A = 5;
    private static final int STOCK_B = 3;

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

    /** Test A 抢购成功的 taskId → userId（跨测试方法保留，结果查询需按归属用户登录态轮询） */
    private final java.util.Map<String, Long> successTaskUsers = new ConcurrentHashMap<>();

    @BeforeAll
    void prepareTestData() {
        cleanTestData();

        SmsSeckillActivity activity = new SmsSeckillActivity();
        activity.setId(ACTIVITY_ID);
        activity.setName("秒杀集成测试活动");
        activity.setStartTime(LocalDateTime.now().minusMinutes(5));
        activity.setEndTime(LocalDateTime.now().plusHours(1));
        activity.setStatus(1);
        activityMapper.insert(activity);

        SmsSeckillProduct spA = new SmsSeckillProduct();
        spA.setId(SP_A);
        spA.setActivityId(ACTIVITY_ID);
        spA.setProductId(1L);
        spA.setSkuId(1L);
        spA.setSeckillPrice(new BigDecimal("7999.00"));
        spA.setSeckillStock(STOCK_A);
        spA.setLimitPerUser(1);
        seckillProductMapper.insert(spA);

        SmsSeckillProduct spB = new SmsSeckillProduct();
        spB.setId(SP_B);
        spB.setActivityId(ACTIVITY_ID);
        spB.setProductId(5L);
        spB.setSkuId(8L);
        spB.setSeckillPrice(new BigDecimal("199.00"));
        spB.setSeckillStock(STOCK_B);
        spB.setLimitPerUser(1);
        seckillProductMapper.insert(spB);

        // 模拟启动预热：库存加载到 Redis
        stringRedisTemplate.opsForValue().set(SeckillServiceImpl.STOCK_KEY_PREFIX + SP_A, String.valueOf(STOCK_A));
        stringRedisTemplate.opsForValue().set(SeckillServiceImpl.STOCK_KEY_PREFIX + SP_B, String.valueOf(STOCK_B));
    }

    @AfterAll
    void cleanTestData() {
        // 订单/订单项按测试用户段清理
        List<Long> testUserIds = new ArrayList<>();
        for (int i = 0; i < USERS_A; i++) {
            testUserIds.add(USER_BASE_A + i);
        }
        testUserIds.add(USER_B);
        List<OmsOrder> orders = orderMapper.selectList(new LambdaQueryWrapper<OmsOrder>()
                .in(OmsOrder::getUserId, testUserIds));
        if (!orders.isEmpty()) {
            orderItemMapper.delete(new LambdaQueryWrapper<OmsOrderItem>()
                    .in(OmsOrderItem::getOrderId, orders.stream().map(OmsOrder::getId).toList()));
            orderMapper.delete(new LambdaQueryWrapper<OmsOrder>()
                    .in(OmsOrder::getId, orders.stream().map(OmsOrder::getId).toList()));
        }
        seckillProductMapper.delete(new LambdaQueryWrapper<SmsSeckillProduct>()
                .in(SmsSeckillProduct::getId, List.of(SP_A, SP_B)));
        activityMapper.deleteById(ACTIVITY_ID);

        List<String> redisKeys = new ArrayList<>();
        redisKeys.add(SeckillServiceImpl.STOCK_KEY_PREFIX + SP_A);
        redisKeys.add(SeckillServiceImpl.STOCK_KEY_PREFIX + SP_B);
        for (long userId : testUserIds) {
            redisKeys.add(SeckillServiceImpl.PURCHASED_KEY_PREFIX + SP_A + ":" + userId);
            redisKeys.add(SeckillServiceImpl.PURCHASED_KEY_PREFIX + SP_B + ":" + userId);
            redisKeys.add(SeckillServiceImpl.RATE_KEY_PREFIX + userId);
        }
        stringRedisTemplate.delete(redisKeys);
    }

    @Test
    @Order(1)
    void concurrentUsersShouldNotOversell() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(USERS_A);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger success = new AtomicInteger();
        AtomicInteger fail = new AtomicInteger();
        for (int i = 0; i < USERS_A; i++) {
            final long userId = USER_BASE_A + i;
            pool.submit(() -> {
                try {
                    start.await();
                    LoginUser.set(new LoginUser(userId, "it-user-" + userId, 0));
                    SeckillOrderVO vo = seckillService.seckillOrder(SP_A);
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
        assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();

        // 不超卖：20 人抢 5 件，恰好 5 成功 15 失败；Redis 预扣计数归零、DB 库存未动
        assertThat(success.get()).isEqualTo(STOCK_A);
        assertThat(fail.get()).isEqualTo(USERS_A - STOCK_A);
        assertThat(stringRedisTemplate.opsForValue().get(SeckillServiceImpl.STOCK_KEY_PREFIX + SP_A))
                .isEqualTo("0");
        assertThat(seckillProductMapper.selectById(SP_A).getSeckillStock()).isEqualTo(STOCK_A);
    }

    @Test
    @Order(2)
    void concurrentRequestsBySameUserShouldRespectLimit() throws Exception {
        final int requests = 10;
        ExecutorService pool = Executors.newFixedThreadPool(requests);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger success = new AtomicInteger();
        AtomicInteger fail = new AtomicInteger();
        for (int i = 0; i < requests; i++) {
            pool.submit(() -> {
                try {
                    start.await();
                    LoginUser.set(new LoginUser(USER_B, "it-user-" + USER_B, 0));
                    seckillService.seckillOrder(SP_B);
                    success.incrementAndGet();
                } catch (Exception e) {
                    fail.incrementAndGet();
                } finally {
                    LoginUser.remove();
                }
            });
        }
        start.countDown();
        pool.shutdown();
        assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();

        // 限购 1 件：无论并发多少次，同一用户只成功 1 次
        assertThat(success.get()).isEqualTo(1);
        assertThat(fail.get()).isEqualTo(requests - 1);
        assertThat(stringRedisTemplate.opsForValue()
                .get(SeckillServiceImpl.PURCHASED_KEY_PREFIX + SP_B + ":" + USER_B)).isEqualTo("1");
        assertThat(stringRedisTemplate.opsForValue().get(SeckillServiceImpl.STOCK_KEY_PREFIX + SP_B))
                .isEqualTo(String.valueOf(STOCK_B - 1));
    }

    @Test
    @Order(3)
    void consumerShouldCreateOrdersForAllReservations() {
        // 真实 MQ 消费异步建单：所有 QUEUED 任务最终变为 SUCCESS 且带订单号
        // （结果查询校验任务归属，需按任务所属用户设置登录态）
        Awaitility.await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofSeconds(1)).untilAsserted(() -> {
            assertThat(successTaskUsers).isNotEmpty();
            for (java.util.Map.Entry<String, Long> entry : successTaskUsers.entrySet()) {
                LoginUser.set(new LoginUser(entry.getValue(), "it-user-" + entry.getValue(), 0));
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

        // 订单数与抢购成功数一致，订单为待支付状态
        List<OmsOrder> orders = orderMapper.selectList(new LambdaQueryWrapper<OmsOrder>()
                .in(OmsOrder::getUserId, List.copyOf(successTaskUsers.values())));
        assertThat(orders).hasSize(successTaskUsers.size());
        assertThat(orders).allMatch(o -> o.getStatus() == 0);

        // DB 秒杀库存被消费端条件扣减，与成功数一致（不超卖最终防线）
        assertThat(seckillProductMapper.selectById(SP_A).getSeckillStock()).isZero();
        assertThat(seckillProductMapper.selectById(SP_B).getSeckillStock()).isEqualTo(STOCK_B - 1);
    }
}
