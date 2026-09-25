package com.mall.seckill.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
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
import com.mall.seckill.dto.SeckillResultVO;
import com.mall.seckill.dto.SeckillTask;
import com.mall.seckill.entity.SmsSeckillActivity;
import com.mall.seckill.entity.SmsSeckillProduct;
import com.mall.seckill.mapper.SeckillActivityMapper;
import com.mall.seckill.mapper.SeckillProductMapper;
import com.mall.seckill.mq.SeckillOrderProducer;
import com.mall.seckill.service.impl.SeckillServiceImpl;
import com.mall.seckill.support.SeckillLua;
import com.mall.security.LoginUser;
import com.mall.user.entity.UmsAddress;
import com.mall.user.mapper.AddressMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 秒杀服务单元测试：覆盖活动列表组装（Redis 实时库存优先/DB 回退）、
 * Lua 预扣各返回码分流、简单限流、MQ 发送失败回滚预扣、任务结果查询与越权、
 * 异步建单成功/失败/幂等跳过、限购与库存归还。
 * mock Mapper/StringRedisTemplate/Producer，Lua 脚本返回值用固定值模拟（真脚本行为由集成测试验证）。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SeckillServiceImplTest {

    private static final Long USER_ID = 2L;
    private static final Long SP_ID = 100L;
    private static final Long ACTIVITY_ID = 10L;
    private static final Long PRODUCT_ID = 1000L;
    private static final Long SKU_ID = 2000L;
    private static final String TASK_ID = "task-abc";
    private static final String TASK_KEY = SeckillServiceImpl.TASK_KEY_PREFIX + TASK_ID;
    private static final String STOCK_KEY = SeckillServiceImpl.STOCK_KEY_PREFIX + SP_ID;
    private static final String PURCHASED_KEY = SeckillServiceImpl.PURCHASED_KEY_PREFIX + SP_ID + ":" + USER_ID;

    @Mock
    private SeckillActivityMapper activityMapper;
    @Mock
    private SeckillProductMapper seckillProductMapper;
    @Mock
    private ProductMapper productMapper;
    @Mock
    private SkuMapper skuMapper;
    @Mock
    private AddressMapper addressMapper;
    @Mock
    private OrderMapper orderMapper;
    @Mock
    private OrderItemMapper orderItemMapper;
    @Mock
    private OrderCloseProducer orderCloseProducer;
    @Mock
    private SeckillOrderProducer seckillOrderProducer;
    @Mock
    private StringRedisTemplate stringRedisTemplate;
    @Mock
    private ValueOperations<String, String> valueOperations;

    @Captor
    private ArgumentCaptor<OmsOrder> orderCaptor;
    @Captor
    private ArgumentCaptor<OmsOrderItem> orderItemCaptor;
    @Captor
    private ArgumentCaptor<String> taskJsonCaptor;
    @Captor
    private ArgumentCaptor<String> taskKeyCaptor;

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    private SeckillServiceImpl seckillService;

    @BeforeEach
    void setUp() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, OmsOrder.class);
        TableInfoHelper.initTableInfo(assistant, OmsOrderItem.class);
        // 秒杀/商品/地址实体同样存在 lambda 条件，需初始化 TableInfo
        TableInfoHelper.initTableInfo(assistant, SmsSeckillActivity.class);
        TableInfoHelper.initTableInfo(assistant, SmsSeckillProduct.class);
        TableInfoHelper.initTableInfo(assistant, PmsProduct.class);
        TableInfoHelper.initTableInfo(assistant, UmsAddress.class);

        // rateLimitPerSecond=3, taskTtlMinutes=30
        seckillService = new SeckillServiceImpl(activityMapper, seckillProductMapper, productMapper, skuMapper,
                addressMapper, orderMapper, orderItemMapper, orderCloseProducer, seckillOrderProducer,
                stringRedisTemplate, objectMapper, 3, 30);

        LoginUser.set(new LoginUser(USER_ID, "user", 0));
        lenient().when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        lenient().when(seckillProductMapper.selectById(SP_ID)).thenReturn(seckillProduct());
        lenient().when(activityMapper.selectById(ACTIVITY_ID)).thenReturn(activity(LocalDateTime.now().minusHours(1),
                LocalDateTime.now().plusHours(1)));
        lenient().when(productMapper.selectById(PRODUCT_ID)).thenReturn(onShelfProduct());
    }

    @AfterEach
    void tearDown() {
        LoginUser.remove();
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    // ---------------- 活动列表 ----------------

    @Test
    void listActivitiesShouldMapProductsAndUseRedisStockFirst() {
        SmsSeckillActivity act = activity(LocalDateTime.now().minusHours(1), LocalDateTime.now().plusHours(1));
        when(activityMapper.selectList(any())).thenReturn(List.of(act));
        when(seckillProductMapper.selectList(any())).thenReturn(List.of(seckillProduct()));
        when(productMapper.selectList(any())).thenReturn(List.of(onShelfProduct()));
        when(valueOperations.get(STOCK_KEY)).thenReturn("7");

        List<SeckillActivityVO> result = seckillService.listActivities();

        assertThat(result).hasSize(1);
        SeckillActivityVO vo = result.get(0);
        assertThat(vo.getId()).isEqualTo(ACTIVITY_ID);
        assertThat(vo.getProducts()).hasSize(1);
        assertThat(vo.getProducts().get(0).getSeckillStock()).isEqualTo(7);
        assertThat(vo.getProducts().get(0).getProductName()).isEqualTo("测试商品");
        assertThat(vo.getProducts().get(0).getOriginalPrice()).isEqualByComparingTo("999.00");
        assertThat(vo.getProducts().get(0).getLimitPerUser()).isEqualTo(1);
    }

    @Test
    void listActivitiesShouldFallbackToDbStockWhenRedisKeyMissing() {
        when(activityMapper.selectList(any())).thenReturn(List.of(
                activity(LocalDateTime.now().minusHours(1), LocalDateTime.now().plusHours(1))));
        when(seckillProductMapper.selectList(any())).thenReturn(List.of(seckillProduct()));
        when(productMapper.selectList(any())).thenReturn(List.of(onShelfProduct()));
        when(valueOperations.get(STOCK_KEY)).thenReturn(null);

        List<SeckillActivityVO> result = seckillService.listActivities();
        assertThat(result.get(0).getProducts().get(0).getSeckillStock()).isEqualTo(10);
    }

    // ---------------- 秒杀下单（预扣 + 投递） ----------------

    @Test
    void seckillOrderShouldSaveQueuedTaskAndSendMqOnSuccess() throws Exception {
        when(valueOperations.increment(anyString())).thenReturn(1L);
        when(stringRedisTemplate.execute(eq(SeckillLua.SECKILL_DEDUCT), anyList(), anyString(), anyString()))
                .thenReturn(1L);

        SeckillOrderVO vo = seckillService.seckillOrder(SP_ID);

        assertThat(vo.getTaskId()).isNotBlank();
        // 任务以 QUEUED 状态写入 Redis（key 随机 taskId）
        verify(valueOperations).set(taskKeyCaptor.capture(), taskJsonCaptor.capture(), any());
        assertThat(taskKeyCaptor.getValue()).isEqualTo(SeckillServiceImpl.TASK_KEY_PREFIX + vo.getTaskId());
        SeckillTask savedTask = objectMapper.readValue(taskJsonCaptor.getValue(), SeckillTask.class);
        assertThat(savedTask.getStatus()).isEqualTo(SeckillTask.STATUS_QUEUED);
        assertThat(savedTask.getUserId()).isEqualTo(USER_ID);
        verify(seckillOrderProducer).sendSeckillOrder(eq(vo.getTaskId()), eq(USER_ID), eq(SP_ID));
    }

    @Test
    void seckillOrderShouldRejectWhenActivityNotOngoing() {
        when(activityMapper.selectById(ACTIVITY_ID)).thenReturn(activity(
                LocalDateTime.now().plusHours(2), LocalDateTime.now().plusHours(3)));
        assertThatThrownBy(() -> seckillService.seckillOrder(SP_ID))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("活动未开始");
    }

    @Test
    void seckillOrderShouldRejectWhenProductOffShelf() {
        PmsProduct offShelf = onShelfProduct();
        offShelf.setStatus(0);
        when(productMapper.selectById(PRODUCT_ID)).thenReturn(offShelf);
        assertThatThrownBy(() -> seckillService.seckillOrder(SP_ID))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("下架");
    }

    @Test
    void seckillOrderShouldRejectWhenSoldOut() {
        when(valueOperations.increment(anyString())).thenReturn(1L);
        when(stringRedisTemplate.execute(eq(SeckillLua.SECKILL_DEDUCT), anyList(), anyString(), anyString()))
                .thenReturn(-1L);
        assertThatThrownBy(() -> seckillService.seckillOrder(SP_ID))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("抢光");
    }

    @Test
    void seckillOrderShouldRejectWhenOverLimit() {
        when(valueOperations.increment(anyString())).thenReturn(1L);
        when(stringRedisTemplate.execute(eq(SeckillLua.SECKILL_DEDUCT), anyList(), anyString(), anyString()))
                .thenReturn(-2L);
        assertThatThrownBy(() -> seckillService.seckillOrder(SP_ID))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("限购");
    }

    @Test
    void seckillOrderShouldRejectWhenStockKeyNotWarmed() {
        when(valueOperations.increment(anyString())).thenReturn(1L);
        when(stringRedisTemplate.execute(eq(SeckillLua.SECKILL_DEDUCT), anyList(), anyString(), anyString()))
                .thenReturn(-3L);
        assertThatThrownBy(() -> seckillService.seckillOrder(SP_ID))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("火爆");
    }

    @Test
    void seckillOrderShouldRejectWhenRateLimitExceeded() {
        when(valueOperations.increment(anyString())).thenReturn(4L);
        assertThatThrownBy(() -> seckillService.seckillOrder(SP_ID))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("频繁");
        verify(stringRedisTemplate, never())
                .execute(eq(SeckillLua.SECKILL_DEDUCT), anyList(), anyString(), anyString());
    }

    @Test
    void seckillOrderShouldRollbackReserveWhenMqSendFailed() {
        when(valueOperations.increment(anyString())).thenReturn(1L);
        when(stringRedisTemplate.execute(eq(SeckillLua.SECKILL_DEDUCT), anyList(), anyString(), anyString()))
                .thenReturn(1L);
        doThrow(new BizException("系统繁忙，请稍后重试"))
                .when(seckillOrderProducer).sendSeckillOrder(anyString(), eq(USER_ID), eq(SP_ID));
        when(valueOperations.get(PURCHASED_KEY)).thenReturn("1");

        assertThatThrownBy(() -> seckillService.seckillOrder(SP_ID))
                .isInstanceOf(BizException.class);
        // 归还库存 + 删除任务
        verify(valueOperations).increment(STOCK_KEY);
        verify(valueOperations).set(eq(PURCHASED_KEY), eq("0"), any());
        verify(stringRedisTemplate).delete(anyString());
    }

    // ---------------- 结果查询 ----------------

    @Test
    void getResultShouldReturnFailWhenTaskMissing() {
        when(valueOperations.get(TASK_KEY)).thenReturn(null);
        SeckillResultVO vo = seckillService.getResult(TASK_ID);
        assertThat(vo.getStatus()).isEqualTo(SeckillTask.STATUS_FAIL);
        assertThat(vo.getOrderNo()).isNull();
    }

    @Test
    void getResultShouldReturnFailForOtherUsersTask() {
        when(valueOperations.get(TASK_KEY)).thenReturn(taskJson(SeckillTask.STATUS_QUEUED, 999L, null));
        SeckillResultVO vo = seckillService.getResult(TASK_ID);
        assertThat(vo.getStatus()).isEqualTo(SeckillTask.STATUS_FAIL);
    }

    @Test
    void getResultShouldReturnQueuedAndSuccess() {
        when(valueOperations.get(TASK_KEY)).thenReturn(taskJson(SeckillTask.STATUS_QUEUED, USER_ID, null));
        assertThat(seckillService.getResult(TASK_ID).getStatus()).isEqualTo(SeckillTask.STATUS_QUEUED);

        when(valueOperations.get(TASK_KEY)).thenReturn(taskJson(SeckillTask.STATUS_SUCCESS, USER_ID, "SN202601"));
        SeckillResultVO vo = seckillService.getResult(TASK_ID);
        assertThat(vo.getStatus()).isEqualTo(SeckillTask.STATUS_SUCCESS);
        assertThat(vo.getOrderNo()).isEqualTo("SN202601");
    }

    // ---------------- 异步建单 ----------------

    @Test
    void createSeckillOrderShouldDeductDbStockAndCreateOrder() throws Exception {
        when(valueOperations.get(TASK_KEY)).thenReturn(taskJson(SeckillTask.STATUS_QUEUED, USER_ID, null));
        when(seckillProductMapper.deductSeckillStock(SP_ID, 1)).thenReturn(1);
        when(productMapper.selectById(PRODUCT_ID)).thenReturn(onShelfProduct());
        when(skuMapper.selectById(SKU_ID)).thenReturn(sku());
        when(addressMapper.selectOne(any())).thenReturn(defaultAddress());
        when(valueOperations.get(STOCK_KEY)).thenReturn("9");

        TransactionSynchronizationManager.initSynchronization();
        seckillService.createSeckillOrder(new SeckillOrderMessage(TASK_ID, USER_ID, SP_ID));

        verify(seckillProductMapper).deductSeckillStock(SP_ID, 1);
        verify(orderMapper).insert(orderCaptor.capture());
        OmsOrder order = orderCaptor.getValue();
        assertThat(order.getUserId()).isEqualTo(USER_ID);
        assertThat(order.getStatus()).isEqualTo(0);
        assertThat(order.getTotalAmount()).isEqualByComparingTo("199.00");
        assertThat(order.getAddressSnapshot()).contains("张三");

        verify(orderItemMapper).insert(orderItemCaptor.capture());
        OmsOrderItem item = orderItemCaptor.getValue();
        assertThat(item.getPrice()).isEqualByComparingTo("199.00");
        assertThat(item.getQuantity()).isEqualTo(1);

        // 事务提交后发送延迟关单消息
        for (TransactionSynchronization sync : TransactionSynchronizationManager.getSynchronizations()) {
            sync.afterCommit();
        }
        verify(orderCloseProducer).sendDelayClose(order.getOrderNo());

        // 任务置 SUCCESS 并回写订单号
        verify(valueOperations).set(eq(TASK_KEY), taskJsonCaptor.capture(), any());
        SeckillTask savedTask = objectMapper.readValue(taskJsonCaptor.getValue(), SeckillTask.class);
        assertThat(savedTask.getStatus()).isEqualTo(SeckillTask.STATUS_SUCCESS);
        assertThat(savedTask.getOrderNo()).isEqualTo(order.getOrderNo());
    }

    @Test
    void createSeckillOrderShouldFailAndRestoreRedisWhenDbStockInsufficient() {
        when(valueOperations.get(TASK_KEY)).thenReturn(taskJson(SeckillTask.STATUS_QUEUED, USER_ID, null));
        when(valueOperations.get(PURCHASED_KEY)).thenReturn("1");
        when(valueOperations.get(STOCK_KEY)).thenReturn("0");
        when(seckillProductMapper.deductSeckillStock(SP_ID, 1)).thenReturn(0);

        seckillService.createSeckillOrder(new SeckillOrderMessage(TASK_ID, USER_ID, SP_ID));

        verify(orderMapper, never()).insert(any(OmsOrder.class));
        verify(valueOperations).increment(STOCK_KEY);
        verify(valueOperations).set(eq(PURCHASED_KEY), eq("0"), any());
        verify(valueOperations).set(eq(TASK_KEY), taskJsonCaptor.capture(), any());
        assertThat(taskJsonCaptor.getValue()).contains(SeckillTask.STATUS_FAIL).contains("抢光");
    }

    @Test
    void createSeckillOrderShouldSkipWhenTaskMissing() {
        when(valueOperations.get(TASK_KEY)).thenReturn(null);
        seckillService.createSeckillOrder(new SeckillOrderMessage(TASK_ID, USER_ID, SP_ID));
        verify(seckillProductMapper, never()).deductSeckillStock(any(), any());
        verify(orderMapper, never()).insert(any(OmsOrder.class));
    }

    @Test
    void createSeckillOrderShouldSkipWhenTaskAlreadyProcessed() {
        when(valueOperations.get(TASK_KEY)).thenReturn(taskJson(SeckillTask.STATUS_SUCCESS, USER_ID, "SN1"));
        seckillService.createSeckillOrder(new SeckillOrderMessage(TASK_ID, USER_ID, SP_ID));
        verify(seckillProductMapper, never()).deductSeckillStock(any(), any());
    }

    @Test
    void failTaskShouldMarkFailAndRestoreRedis() {
        when(valueOperations.get(TASK_KEY)).thenReturn(taskJson(SeckillTask.STATUS_QUEUED, USER_ID, null));
        when(valueOperations.get(PURCHASED_KEY)).thenReturn("1");
        when(valueOperations.get(STOCK_KEY)).thenReturn("5");

        seckillService.failTask(new SeckillOrderMessage(TASK_ID, USER_ID, SP_ID), "系统繁忙");

        verify(valueOperations).increment(STOCK_KEY);
        verify(valueOperations).set(eq(PURCHASED_KEY), eq("0"), any());
        verify(valueOperations).set(eq(TASK_KEY), taskJsonCaptor.capture(), any());
        assertThat(taskJsonCaptor.getValue()).contains(SeckillTask.STATUS_FAIL).contains("系统繁忙");
    }

    // ---------------- 测试数据 ----------------

    private SmsSeckillActivity activity(LocalDateTime start, LocalDateTime end) {
        SmsSeckillActivity act = new SmsSeckillActivity();
        act.setId(ACTIVITY_ID);
        act.setName("测试秒杀活动");
        act.setStartTime(start);
        act.setEndTime(end);
        act.setStatus(1);
        return act;
    }

    private SmsSeckillProduct seckillProduct() {
        SmsSeckillProduct sp = new SmsSeckillProduct();
        sp.setId(SP_ID);
        sp.setActivityId(ACTIVITY_ID);
        sp.setProductId(PRODUCT_ID);
        sp.setSkuId(SKU_ID);
        sp.setSeckillPrice(new BigDecimal("199.00"));
        sp.setSeckillStock(10);
        sp.setLimitPerUser(1);
        return sp;
    }

    private PmsProduct onShelfProduct() {
        PmsProduct product = new PmsProduct();
        product.setId(PRODUCT_ID);
        product.setName("测试商品");
        product.setMainPic("http://pic/test.png");
        product.setPrice(new BigDecimal("999.00"));
        product.setStatus(1);
        return product;
    }

    private PmsSku sku() {
        PmsSku sku = new PmsSku();
        sku.setId(SKU_ID);
        sku.setProductId(PRODUCT_ID);
        sku.setSkuCode("SKU-TEST");
        sku.setSpecJson("{\"颜色\":\"黑色\"}");
        sku.setPrice(new BigDecimal("999.00"));
        sku.setStock(100);
        return sku;
    }

    private UmsAddress defaultAddress() {
        UmsAddress address = new UmsAddress();
        address.setId(1L);
        address.setUserId(USER_ID);
        address.setReceiverName("张三");
        address.setReceiverPhone("13800000000");
        address.setProvince("广东省");
        address.setCity("深圳市");
        address.setDistrict("南山区");
        address.setDetailAddress("科技园 1 号");
        address.setIsDefault(1);
        return address;
    }

    private String taskJson(String status, Long userId, String orderNo) {
        try {
            SeckillTask task = new SeckillTask();
            task.setUserId(userId);
            task.setSeckillProductId(SP_ID);
            task.setStatus(status);
            task.setOrderNo(orderNo);
            return objectMapper.writeValueAsString(task);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
