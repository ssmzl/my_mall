package com.mall.admin.service.impl;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.mall.admin.dto.AdminOrderPageQueryDTO;
import com.mall.common.BizException;
import com.mall.common.PageResult;
import com.mall.order.dto.OrderVO;
import com.mall.order.entity.OmsOrder;
import com.mall.order.entity.OmsOrderItem;
import com.mall.order.mapper.OrderItemMapper;
import com.mall.order.mapper.OrderMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 管理端订单单元测试：全量分页组装、发货条件更新（幂等/非法状态）。
 */
@ExtendWith(MockitoExtension.class)
class AdminOrderServiceImplTest {

    private static final String ORDER_NO = "20260925120000000001";

    @Mock
    private OrderMapper orderMapper;

    @Mock
    private OrderItemMapper orderItemMapper;

    private AdminOrderServiceImpl service;

    @BeforeEach
    void setUp() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, OmsOrder.class);
        TableInfoHelper.initTableInfo(assistant, OmsOrderItem.class);
        service = new AdminOrderServiceImpl(orderMapper, orderItemMapper);
    }

    @Test
    @SuppressWarnings("unchecked")
    void pageShouldAssembleItems() {
        OmsOrder order = order(1);
        Page<OmsOrder> page = new Page<>(1, 10);
        page.setRecords(List.of(order));
        page.setTotal(1);
        when(orderMapper.selectPage(any(), any())).thenReturn(page);
        OmsOrderItem item = new OmsOrderItem();
        item.setId(100L);
        item.setOrderId(1L);
        item.setProductId(11L);
        item.setSkuId(21L);
        item.setProductName("测试商品");
        item.setPrice(new BigDecimal("9.90"));
        item.setQuantity(2);
        when(orderItemMapper.selectList(any())).thenReturn(List.of(item));

        PageResult<OrderVO> result = service.page(new AdminOrderPageQueryDTO());

        assertThat(result.getRecords()).hasSize(1);
        OrderVO vo = result.getRecords().get(0);
        assertThat(vo.getOrderNo()).isEqualTo(ORDER_NO);
        assertThat(vo.getItems()).hasSize(1);
        assertThat(vo.getItems().get(0).getProductName()).isEqualTo("测试商品");
    }

    @Test
    @SuppressWarnings("unchecked")
    void pageShouldReturnEmptyWhenNoOrders() {
        Page<OmsOrder> page = new Page<>(1, 10);
        page.setRecords(List.of());
        when(orderMapper.selectPage(any(), any())).thenReturn(page);

        PageResult<OrderVO> result = service.page(new AdminOrderPageQueryDTO());

        assertThat(result.getRecords()).isEmpty();
        verify(orderItemMapper, never()).selectList(any());
    }

    @Test
    void deliverShouldUpdatePaidToDelivered() {
        when(orderMapper.update(any(), any())).thenReturn(1);

        service.deliver(ORDER_NO);

        verify(orderMapper).update(any(), any());
        // 条件更新成功时无需回查订单
        verify(orderMapper, never()).selectOne(any());
    }

    @Test
    void deliverShouldBeIdempotentWhenAlreadyDelivered() {
        when(orderMapper.update(any(), any())).thenReturn(0);
        lenient().when(orderMapper.selectOne(any())).thenReturn(order(2));

        service.deliver(ORDER_NO);
    }

    @Test
    void deliverShouldRejectUnpaidOrder() {
        when(orderMapper.update(any(), any())).thenReturn(0);
        when(orderMapper.selectOne(any())).thenReturn(order(0));

        assertThatThrownBy(() -> service.deliver(ORDER_NO))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("不可发货");
    }

    @Test
    void deliverShouldRejectWhenOrderNotExists() {
        when(orderMapper.update(any(), any())).thenReturn(0);
        when(orderMapper.selectOne(any())).thenReturn(null);

        assertThatThrownBy(() -> service.deliver(ORDER_NO))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("订单不存在");
    }

    @Test
    void deliverShouldCallConditionalUpdate() {
        // 发货走条件更新（update(null, wrapper)），不整单覆盖
        when(orderMapper.update(any(), any())).thenReturn(1);
        com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<OmsOrder>[] captured =
                new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper[1];
        when(orderMapper.update(eq(null), any())).thenAnswer(inv -> {
            captured[0] = inv.getArgument(1);
            return 1;
        });

        service.deliver(ORDER_NO);

        assertThat(captured[0].getSqlSet()).contains("status");
    }

    private static OmsOrder order(int status) {
        OmsOrder o = new OmsOrder();
        o.setId(1L);
        o.setOrderNo(ORDER_NO);
        o.setUserId(9L);
        o.setTotalAmount(new BigDecimal("19.80"));
        o.setStatus(status);
        return o;
    }
}
