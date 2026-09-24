package com.mall.order.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mall.cart.service.CartService;
import com.mall.common.BizException;
import com.mall.common.PageResult;
import com.mall.order.dto.CreateOrderDTO;
import com.mall.order.dto.CreateOrderVO;
import com.mall.order.dto.OrderPageQueryDTO;
import com.mall.order.dto.OrderVO;
import com.mall.order.entity.OmsOrder;
import com.mall.order.entity.OmsOrderItem;
import com.mall.order.mapper.OrderItemMapper;
import com.mall.order.mapper.OrderMapper;
import com.mall.order.mq.OrderCloseProducer;
import com.mall.order.service.impl.OrderServiceImpl;
import com.mall.product.entity.PmsProduct;
import com.mall.product.entity.PmsSku;
import com.mall.product.mapper.ProductMapper;
import com.mall.product.mapper.SkuMapper;
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
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 订单服务单元测试：覆盖防重 Token 一次性消费、下单扣库存防超卖、订单/订单项快照、
 * 事务提交后副作用（延迟关单消息 + 清购物车）、支付/取消/超时关单幂等与回补库存、分页/详情与越权。
 * mock Mapper/RedisTemplate/CartService/OrderCloseProducer。
 */
@ExtendWith(MockitoExtension.class)
class OrderServiceImplTest {

    private static final Long USER_ID = 1L;
    private static final Long ADDRESS_ID = 10L;
    private static final Long SKU_ID = 1001L;
    private static final Long SKU_ID_2 = 1002L;
    private static final Long PRODUCT_ID = 100L;
    private static final Long ORDER_ID = 500L;
    private static final String ORDER_NO = "20260923120000000001";
    private static final String TOKEN = "tok-abc123";
    private static final String TOKEN_KEY = OrderServiceImpl.ORDER_TOKEN_KEY_PREFIX + TOKEN;

    @Mock
    private OrderMapper orderMapper;

    @Mock
    private OrderItemMapper orderItemMapper;

    @Mock
    private SkuMapper skuMapper;

    @Mock
    private ProductMapper productMapper;

    @Mock
    private AddressMapper addressMapper;

    @Mock
    private CartService cartService;

    @Mock
    private OrderCloseProducer orderCloseProducer;

    @Mock
    private StringRedisTemplate stringRedisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    @Captor
    private ArgumentCaptor<OmsOrder> orderCaptor;

    @Captor
    private ArgumentCaptor<OmsOrderItem> orderItemCaptor;

    @Captor
    private ArgumentCaptor<Wrapper<OmsOrder>> orderUpdateCaptor;

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    private OrderServiceImpl orderService;

    @BeforeEach
    void setUp() {
        // 初始化 MyBatis-Plus 元数据：订单/订单项的 lambda 条件依赖 TableInfo
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, OmsOrder.class);
        TableInfoHelper.initTableInfo(assistant, OmsOrderItem.class);

        orderService = new OrderServiceImpl(orderMapper, orderItemMapper, skuMapper, productMapper,
                addressMapper, cartService, orderCloseProducer, stringRedisTemplate, objectMapper, 10);
        LoginUser.set(new LoginUser(USER_ID, "testuser", 0));
        // afterCommit 注册需要事务同步处于激活状态
        TransactionSynchronizationManager.initSynchronization();
        lenient().when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        // 默认插入订单时回填自增ID
        lenient().doAnswer(invocation -> {
            OmsOrder order = invocation.getArgument(0);
            order.setId(ORDER_ID);
            return 1;
        }).when(orderMapper).insert(any(OmsOrder.class));
        lenient().when(orderItemMapper.insert(any(OmsOrderItem.class))).thenReturn(1);
    }

    @AfterEach
    void tearDown() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
        LoginUser.remove();
    }

    // ---------------- 防重 Token ----------------

    @Test
    void getTokenShouldStoreTokenBoundToUserWithTtl() {
        orderService.getOrderToken();

        verify(valueOperations).set(anyString(), eq(String.valueOf(USER_ID)), eq(Duration.ofMinutes(10)));
    }

    // ---------------- 下单 ----------------

    @Test
    void createOrderShouldCreateOrderAndItemsAndDeductStock() throws Exception {
        stubTokenValid();
        when(addressMapper.selectById(ADDRESS_ID)).thenReturn(buildAddress());
        stubSkuValid(SKU_ID);
        when(skuMapper.deductStock(SKU_ID, 2)).thenReturn(1);

        CreateOrderVO vo = orderService.createOrder(dto(TOKEN, item(SKU_ID, 2)));

        verify(skuMapper).deductStock(SKU_ID, 2);
        verify(orderMapper).insert(orderCaptor.capture());
        OmsOrder order = orderCaptor.getValue();
        assertThat(order.getOrderNo()).isNotBlank();
        assertThat(order.getUserId()).isEqualTo(USER_ID);
        // 总金额 = 3999.00 × 2
        assertThat(order.getTotalAmount()).isEqualByComparingTo(new BigDecimal("7998.00"));
        assertThat(order.getStatus()).isZero();
        // 地址快照与前端 OrderDetailView 解析契约一致
        assertThat(order.getAddressSnapshot()).contains("张三").contains("详细地址1");

        verify(orderItemMapper).insert(orderItemCaptor.capture());
        OmsOrderItem item = orderItemCaptor.getValue();
        assertThat(item.getOrderId()).isEqualTo(ORDER_ID);
        assertThat(item.getProductId()).isEqualTo(PRODUCT_ID);
        assertThat(item.getProductName()).isEqualTo("测试手机");
        // 规格解析为值 " / " 拼接（与购物车/前端详情页约定一致）
        assertThat(item.getSkuSpec()).isEqualTo("黑色 / 256GB");
        assertThat(item.getPrice()).isEqualByComparingTo(new BigDecimal("3999.00"));
        assertThat(item.getQuantity()).isEqualTo(2);

        assertThat(vo.getOrderId()).isEqualTo(ORDER_ID);
        assertThat(vo.getOrderNo()).isEqualTo(order.getOrderNo());
        assertThat(vo.getPayAmount()).isEqualByComparingTo(new BigDecimal("7998.00"));
    }

    @Test
    void createOrderShouldMergeSameSkuItems() {
        stubTokenValid();
        when(addressMapper.selectById(ADDRESS_ID)).thenReturn(buildAddress());
        stubSkuValid(SKU_ID);
        when(skuMapper.deductStock(SKU_ID, 3)).thenReturn(1);

        orderService.createOrder(dto(TOKEN, item(SKU_ID, 1), item(SKU_ID, 2)));

        // 同 SKU 合并后一次扣减 3 件，只生成一条订单项
        verify(skuMapper).deductStock(SKU_ID, 3);
        verify(orderItemMapper).insert(any(OmsOrderItem.class));
    }

    @Test
    void createOrderShouldTriggerAfterCommitSideEffects() {
        stubTokenValid();
        when(addressMapper.selectById(ADDRESS_ID)).thenReturn(buildAddress());
        stubSkuValid(SKU_ID);
        when(skuMapper.deductStock(SKU_ID, 1)).thenReturn(1);

        CreateOrderVO vo = orderService.createOrder(dto(TOKEN, item(SKU_ID, 1)));

        // 模拟事务提交：触发 afterCommit 副作用
        triggerAfterCommit();

        verify(orderCloseProducer).sendDelayClose(vo.getOrderNo());
        verify(cartService).remove(SKU_ID);
    }

    @Test
    void createOrderShouldNotSendDelayMessageBeforeCommit() {
        stubTokenValid();
        when(addressMapper.selectById(ADDRESS_ID)).thenReturn(buildAddress());
        stubSkuValid(SKU_ID);
        when(skuMapper.deductStock(SKU_ID, 1)).thenReturn(1);

        orderService.createOrder(dto(TOKEN, item(SKU_ID, 1)));

        // 事务未提交前不应发送消息/清购物车
        verify(orderCloseProducer, never()).sendDelayClose(anyString());
        verify(cartService, never()).remove(anyLong());
    }

    @Test
    void createOrderShouldRejectWhenTokenMissing() {
        when(valueOperations.get(TOKEN_KEY)).thenReturn(null);

        assertThatThrownBy(() -> orderService.createOrder(dto(TOKEN, item(SKU_ID, 1))))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("请刷新");

        verify(orderMapper, never()).insert(any(OmsOrder.class));
        verify(skuMapper, never()).deductStock(anyLong(), anyInt());
    }

    @Test
    void createOrderShouldRejectWhenTokenBoundToOtherUser() {
        when(valueOperations.get(TOKEN_KEY)).thenReturn("999");

        assertThatThrownBy(() -> orderService.createOrder(dto(TOKEN, item(SKU_ID, 1))))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("请刷新");
    }

    @Test
    void createOrderShouldRejectWhenTokenAlreadyConsumed() {
        // 并发重复提交：get 时仍存在，但 DELETE 未成功（另一请求已消费）
        when(valueOperations.get(TOKEN_KEY)).thenReturn(String.valueOf(USER_ID));
        when(stringRedisTemplate.delete(TOKEN_KEY)).thenReturn(false);

        assertThatThrownBy(() -> orderService.createOrder(dto(TOKEN, item(SKU_ID, 1))))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("请勿重复提交");

        verify(orderMapper, never()).insert(any(OmsOrder.class));
    }

    @Test
    void createOrderShouldRejectWhenAddressNotOwned() {
        stubTokenValid();
        UmsAddress otherAddress = buildAddress();
        otherAddress.setUserId(999L);
        when(addressMapper.selectById(ADDRESS_ID)).thenReturn(otherAddress);

        assertThatThrownBy(() -> orderService.createOrder(dto(TOKEN, item(SKU_ID, 1))))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("收货地址不存在");

        verify(skuMapper, never()).deductStock(anyLong(), anyInt());
    }

    @Test
    void createOrderShouldThrowWhenStockNotEnough() {
        stubTokenValid();
        when(addressMapper.selectById(ADDRESS_ID)).thenReturn(buildAddress());
        stubSkuValid(SKU_ID);
        when(skuMapper.deductStock(SKU_ID, 5)).thenReturn(0);

        assertThatThrownBy(() -> orderService.createOrder(dto(TOKEN, item(SKU_ID, 5))))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("库存不足");

        verify(orderMapper, never()).insert(any(OmsOrder.class));
    }

    @Test
    void createOrderShouldThrowWhenProductOffShelf() {
        stubTokenValid();
        when(addressMapper.selectById(ADDRESS_ID)).thenReturn(buildAddress());
        PmsSku sku = buildSku(SKU_ID);
        when(skuMapper.selectById(SKU_ID)).thenReturn(sku);
        PmsProduct offShelf = buildProduct();
        offShelf.setStatus(0);
        when(productMapper.selectById(PRODUCT_ID)).thenReturn(offShelf);

        assertThatThrownBy(() -> orderService.createOrder(dto(TOKEN, item(SKU_ID, 1))))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("已下架");

        verify(orderMapper, never()).insert(any(OmsOrder.class));
    }

    // ---------------- 支付 ----------------

    @Test
    void payOrderShouldUpdateStatusAndIncreaseSales() {
        OmsOrder order = buildOrder(ORDER_ID, ORDER_NO, 0);
        when(orderMapper.update(isNull(), orderUpdateCaptor.capture())).thenReturn(1);
        when(orderMapper.selectOne(any())).thenReturn(order);
        when(orderItemMapper.selectList(any())).thenReturn(List.of(buildOrderItem(SKU_ID, PRODUCT_ID, 2)));

        orderService.payOrder(ORDER_NO);

        verify(productMapper).increaseSales(PRODUCT_ID, 2);
    }

    @Test
    void payOrderShouldBeIdempotentWhenAlreadyPaid() {
        // 条件更新影响 0 行，订单已支付 → 幂等返回成功，不再累加销量
        when(orderMapper.update(any(), any())).thenReturn(0);
        when(orderMapper.selectOne(any())).thenReturn(buildOrder(ORDER_ID, ORDER_NO, 1));

        orderService.payOrder(ORDER_NO);

        verify(productMapper, never()).increaseSales(anyLong(), anyInt());
    }

    @Test
    void payOrderShouldThrowWhenCancelled() {
        when(orderMapper.update(any(), any())).thenReturn(0);
        when(orderMapper.selectOne(any())).thenReturn(buildOrder(ORDER_ID, ORDER_NO, 4));

        assertThatThrownBy(() -> orderService.payOrder(ORDER_NO))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("不可支付");
    }

    // ---------------- 主动取消 ----------------

    @Test
    void cancelOrderShouldCancelAndRestock() {
        OmsOrder order = buildOrder(ORDER_ID, ORDER_NO, 0);
        when(orderMapper.selectOne(any())).thenReturn(order);
        when(orderMapper.update(isNull(), orderUpdateCaptor.capture())).thenReturn(1);
        when(orderItemMapper.selectList(any())).thenReturn(List.of(
                buildOrderItem(SKU_ID, PRODUCT_ID, 2), buildOrderItem(SKU_ID_2, PRODUCT_ID, 1)));

        orderService.cancelOrder(ORDER_NO);

        verify(skuMapper).restockStock(SKU_ID, 2);
        verify(skuMapper).restockStock(SKU_ID_2, 1);
    }

    @Test
    void cancelOrderShouldBeIdempotentWhenAlreadyCancelled() {
        when(orderMapper.selectOne(any())).thenReturn(buildOrder(ORDER_ID, ORDER_NO, 4));
        when(orderMapper.update(any(), any())).thenReturn(0);

        orderService.cancelOrder(ORDER_NO);

        // 已取消幂等返回，不重复回补库存
        verify(skuMapper, never()).restockStock(anyLong(), anyInt());
    }

    @Test
    void cancelOrderShouldThrowWhenPaid() {
        when(orderMapper.selectOne(any())).thenReturn(buildOrder(ORDER_ID, ORDER_NO, 1));
        when(orderMapper.update(any(), any())).thenReturn(0);

        assertThatThrownBy(() -> orderService.cancelOrder(ORDER_NO))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("不可取消");
    }

    @Test
    void cancelOrderShouldRejectOtherUsersOrder() {
        OmsOrder otherOrder = buildOrder(ORDER_ID, ORDER_NO, 0);
        otherOrder.setUserId(999L);
        when(orderMapper.selectOne(any())).thenReturn(otherOrder);

        assertThatThrownBy(() -> orderService.cancelOrder(ORDER_NO))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("订单不存在");

        verify(orderMapper, never()).update(any(), any());
    }

    // ---------------- 超时关单 ----------------

    @Test
    void closeTimeoutOrderShouldCancelUnpaidOrderAndRestock() {
        when(orderMapper.selectOne(any())).thenReturn(buildOrder(ORDER_ID, ORDER_NO, 0));
        when(orderMapper.update(any(), any())).thenReturn(1);
        when(orderItemMapper.selectList(any())).thenReturn(List.of(buildOrderItem(SKU_ID, PRODUCT_ID, 2)));

        orderService.closeTimeoutOrder(ORDER_NO);

        verify(skuMapper).restockStock(SKU_ID, 2);
    }

    @Test
    void closeTimeoutOrderShouldSkipWhenOrderMissing() {
        // 下单事务回滚等场景可能消费到无对应订单的消息，直接确认消费
        when(orderMapper.selectOne(any())).thenReturn(null);

        orderService.closeTimeoutOrder(ORDER_NO);

        verify(orderMapper, never()).update(any(), any());
        verify(skuMapper, never()).restockStock(anyLong(), anyInt());
    }

    @Test
    void closeTimeoutOrderShouldSkipWhenAlreadyPaid() {
        // 已支付订单不关单、不回补库存（支付与关单并发时只有一方生效）
        when(orderMapper.selectOne(any())).thenReturn(buildOrder(ORDER_ID, ORDER_NO, 1));
        when(orderMapper.update(any(), any())).thenReturn(0);

        orderService.closeTimeoutOrder(ORDER_NO);

        verify(skuMapper, never()).restockStock(anyLong(), anyInt());
    }

    // ---------------- 分页 / 详情 ----------------

    @Test
    void pageMyOrdersShouldReturnOrdersWithItems() {
        Page<OmsOrder> page = new Page<>(1, 10);
        page.setRecords(List.of(buildOrder(ORDER_ID, ORDER_NO, 0)));
        page.setTotal(1);
        when(orderMapper.selectPage(any(), any())).thenReturn(page);
        when(orderItemMapper.selectList(any())).thenReturn(List.of(buildOrderItem(SKU_ID, PRODUCT_ID, 2)));

        PageResult<OrderVO> result = orderService.pageMyOrders(new OrderPageQueryDTO());

        assertThat(result.getRecords()).hasSize(1);
        OrderVO vo = result.getRecords().get(0);
        assertThat(vo.getOrderNo()).isEqualTo(ORDER_NO);
        assertThat(vo.getStatus()).isZero();
        assertThat(vo.getItems()).hasSize(1);
        assertThat(vo.getItems().get(0).getSkuId()).isEqualTo(SKU_ID);
        assertThat(result.getTotal()).isEqualTo(1);
    }

    @Test
    void detailShouldReturnOwnedOrderWithItems() {
        when(orderMapper.selectOne(any())).thenReturn(buildOrder(ORDER_ID, ORDER_NO, 0));
        when(orderItemMapper.selectList(any())).thenReturn(List.of(buildOrderItem(SKU_ID, PRODUCT_ID, 2)));

        OrderVO vo = orderService.detail(ORDER_NO);

        assertThat(vo.getId()).isEqualTo(ORDER_ID);
        assertThat(vo.getItems()).hasSize(1);
    }

    @Test
    void detailShouldRejectOtherUsersOrder() {
        OmsOrder otherOrder = buildOrder(ORDER_ID, ORDER_NO, 0);
        otherOrder.setUserId(999L);
        when(orderMapper.selectOne(any())).thenReturn(otherOrder);

        assertThatThrownBy(() -> orderService.detail(ORDER_NO))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("订单不存在");
    }

    // ---------------- 测试辅助 ----------------

    /** 触发当前注册的事务同步 afterCommit（模拟事务提交） */
    private void triggerAfterCommit() {
        for (TransactionSynchronization synchronization :
                new java.util.ArrayList<>(TransactionSynchronizationManager.getSynchronizations())) {
            synchronization.afterCommit();
        }
    }

    private void stubTokenValid() {
        when(valueOperations.get(TOKEN_KEY)).thenReturn(String.valueOf(USER_ID));
        when(stringRedisTemplate.delete(TOKEN_KEY)).thenReturn(true);
    }

    private void stubSkuValid(Long skuId) {
        when(skuMapper.selectById(skuId)).thenReturn(buildSku(skuId));
        when(productMapper.selectById(PRODUCT_ID)).thenReturn(buildProduct());
    }

    private CreateOrderDTO dto(String token, CreateOrderDTO.CreateOrderItemDTO... items) {
        CreateOrderDTO dto = new CreateOrderDTO();
        dto.setToken(token);
        dto.setAddressId(ADDRESS_ID);
        dto.setItems(List.of(items));
        return dto;
    }

    private CreateOrderDTO.CreateOrderItemDTO item(Long skuId, int quantity) {
        CreateOrderDTO.CreateOrderItemDTO item = new CreateOrderDTO.CreateOrderItemDTO();
        item.setSkuId(skuId);
        item.setQuantity(quantity);
        return item;
    }

    private UmsAddress buildAddress() {
        UmsAddress address = new UmsAddress();
        address.setId(ADDRESS_ID);
        address.setUserId(USER_ID);
        address.setReceiverName("张三");
        address.setReceiverPhone("13800138000");
        address.setProvince("广东省");
        address.setCity("深圳市");
        address.setDistrict("南山区");
        address.setDetailAddress("详细地址1");
        return address;
    }

    private PmsSku buildSku(Long skuId) {
        PmsSku sku = new PmsSku();
        sku.setId(skuId);
        sku.setProductId(PRODUCT_ID);
        sku.setSkuCode("P100-BLACK-256G");
        sku.setSpecJson("{\"颜色\":\"黑色\",\"存储\":\"256GB\"}");
        sku.setPrice(new BigDecimal("3999.00"));
        sku.setStock(5);
        return sku;
    }

    private PmsProduct buildProduct() {
        PmsProduct product = new PmsProduct();
        product.setId(PRODUCT_ID);
        product.setCategoryId(11L);
        product.setName("测试手机");
        product.setPrice(new BigDecimal("3999.00"));
        product.setStock(50);
        product.setSales(120);
        product.setStatus(1);
        return product;
    }

    private OmsOrder buildOrder(Long id, String orderNo, int status) {
        OmsOrder order = new OmsOrder();
        order.setId(id);
        order.setOrderNo(orderNo);
        order.setUserId(USER_ID);
        order.setTotalAmount(new BigDecimal("7998.00"));
        order.setStatus(status);
        order.setAddressSnapshot("{\"receiverName\":\"张三\"}");
        return order;
    }

    private OmsOrderItem buildOrderItem(Long skuId, Long productId, int quantity) {
        OmsOrderItem item = new OmsOrderItem();
        item.setId(1L);
        item.setOrderId(ORDER_ID);
        item.setProductId(productId);
        item.setSkuId(skuId);
        item.setProductName("测试手机");
        item.setSkuSpec("黑色 / 256GB");
        item.setPrice(new BigDecimal("3999.00"));
        item.setQuantity(quantity);
        return item;
    }
}
