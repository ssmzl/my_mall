package com.mall.admin.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.mall.admin.dto.AdminOrderPageQueryDTO;
import com.mall.admin.service.AdminOrderService;
import com.mall.common.BizException;
import com.mall.common.PageResult;
import com.mall.order.dto.OrderItemVO;
import com.mall.order.dto.OrderVO;
import com.mall.order.entity.OmsOrder;
import com.mall.order.entity.OmsOrderItem;
import com.mall.order.mapper.OrderItemMapper;
import com.mall.order.mapper.OrderMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 管理端订单实现。
 *
 * <p>发货采用条件更新（仅 1已支付 → 2已发货）：与支付/取消并发时数据库原子性保证
 * 只有合法状态流转生效；重复发货影响 0 行，按当前状态幂等处理。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AdminOrderServiceImpl implements AdminOrderService {

    /** 订单状态：0待支付 1已支付 2已发货 3已完成 4已取消 */
    private static final int STATUS_PAID = 1;
    private static final int STATUS_DELIVERED = 2;

    private final OrderMapper orderMapper;
    private final OrderItemMapper orderItemMapper;

    @Override
    public PageResult<OrderVO> page(AdminOrderPageQueryDTO query) {
        Page<OmsOrder> page = new Page<>(Math.max(query.getPageNum(), 1),
                Math.min(Math.max(query.getPageSize(), 1), 50));
        LambdaQueryWrapper<OmsOrder> wrapper = new LambdaQueryWrapper<OmsOrder>()
                .eq(query.getStatus() != null, OmsOrder::getStatus, query.getStatus())
                .like(StringUtils.hasText(query.getOrderNo()), OmsOrder::getOrderNo, query.getOrderNo())
                .orderByDesc(OmsOrder::getCreateTime)
                .orderByDesc(OmsOrder::getId);
        Page<OmsOrder> result = orderMapper.selectPage(page, wrapper);

        Map<Long, List<OrderItemVO>> itemsByOrderId = loadItemsGrouped(
                result.getRecords().stream().map(OmsOrder::getId).collect(Collectors.toList()));
        List<OrderVO> vos = result.getRecords().stream()
                .map(order -> toVO(order, itemsByOrderId.getOrDefault(order.getId(), List.of())))
                .collect(Collectors.toList());
        PageResult<OrderVO> pageResult = new PageResult<>();
        pageResult.setRecords(vos);
        pageResult.setTotal(result.getTotal());
        pageResult.setCurrent(result.getCurrent());
        pageResult.setSize(result.getSize());
        return pageResult;
    }

    @Override
    public void deliver(String orderNo) {
        int updated = orderMapper.update(null, new LambdaUpdateWrapper<OmsOrder>()
                .eq(OmsOrder::getOrderNo, orderNo)
                .eq(OmsOrder::getStatus, STATUS_PAID)
                .set(OmsOrder::getStatus, STATUS_DELIVERED));
        if (updated == 0) {
            OmsOrder order = requireOrderByNo(orderNo);
            if (order.getStatus() == STATUS_DELIVERED) {
                // 幂等：已发货直接返回成功
                log.info("重复发货请求，幂等处理: orderNo={}", orderNo);
                return;
            }
            throw new BizException("当前订单状态不可发货");
        }
        log.info("订单发货成功: orderNo={}", orderNo);
    }

    // ---------------- 内部方法 ----------------

    private OmsOrder requireOrderByNo(String orderNo) {
        OmsOrder order = orderMapper.selectOne(new LambdaQueryWrapper<OmsOrder>()
                .eq(OmsOrder::getOrderNo, orderNo));
        if (order == null) {
            throw new BizException("订单不存在");
        }
        return order;
    }

    private Map<Long, List<OrderItemVO>> loadItemsGrouped(List<Long> orderIds) {
        if (orderIds.isEmpty()) {
            return Map.of();
        }
        return orderItemMapper.selectList(new LambdaQueryWrapper<OmsOrderItem>()
                        .in(OmsOrderItem::getOrderId, orderIds)).stream()
                .collect(Collectors.groupingBy(OmsOrderItem::getOrderId,
                        Collectors.mapping(this::toItemVO, Collectors.toList())));
    }

    private OrderVO toVO(OmsOrder order, List<OrderItemVO> items) {
        OrderVO vo = new OrderVO();
        vo.setId(order.getId());
        vo.setOrderNo(order.getOrderNo());
        vo.setUserId(order.getUserId());
        vo.setTotalAmount(order.getTotalAmount());
        vo.setStatus(order.getStatus());
        vo.setAddressSnapshot(order.getAddressSnapshot());
        vo.setPayTime(order.getPayTime());
        vo.setCreateTime(order.getCreateTime());
        vo.setItems(items);
        return vo;
    }

    private OrderItemVO toItemVO(OmsOrderItem item) {
        OrderItemVO vo = new OrderItemVO();
        vo.setId(item.getId());
        vo.setOrderId(item.getOrderId());
        vo.setProductId(item.getProductId());
        vo.setSkuId(item.getSkuId());
        vo.setProductName(item.getProductName());
        vo.setSkuSpec(item.getSkuSpec());
        vo.setPrice(item.getPrice());
        vo.setQuantity(item.getQuantity());
        return vo;
    }
}
