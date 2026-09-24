package com.mall.order.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mall.cart.service.CartService;
import com.mall.common.BizException;
import com.mall.common.PageResult;
import com.mall.order.dto.CreateOrderDTO;
import com.mall.order.dto.CreateOrderVO;
import com.mall.order.dto.OrderItemVO;
import com.mall.order.dto.OrderPageQueryDTO;
import com.mall.order.dto.OrderVO;
import com.mall.order.entity.OmsOrder;
import com.mall.order.entity.OmsOrderItem;
import com.mall.order.mapper.OrderItemMapper;
import com.mall.order.mapper.OrderMapper;
import com.mall.order.mq.OrderCloseProducer;
import com.mall.order.service.OrderService;
import com.mall.product.entity.PmsProduct;
import com.mall.product.entity.PmsSku;
import com.mall.product.mapper.ProductMapper;
import com.mall.product.mapper.SkuMapper;
import com.mall.security.LoginUser;
import com.mall.user.entity.UmsAddress;
import com.mall.user.mapper.AddressMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;

/**
 * 订单服务实现。
 *
 * <p>关键设计：
 * <ul>
 *   <li>防重 Token：Redis SETNX 语义的一次性令牌（key 绑定用户），下单时原子消费，
 *       重复提交/过期 Token 直接拒绝；</li>
 *   <li>不超卖：SKU 库存条件扣减（UPDATE ... WHERE stock >= quantity），
 *       由数据库行锁保证并发下不超卖，影响行数为 0 即库存不足；</li>
 *   <li>延迟关单：下单事务提交后（afterCommit）发送 RocketMQ 延迟消息，
 *       避免事务回滚后消费到脏消息；</li>
 *   <li>关单/取消幂等：仅「待支付 → 已取消」的条件更新，影响行数为 0 时按当前状态幂等处理，
 *       保证支付与超时关单并发时只有一方生效、库存只回补一次。</li>
 * </ul>
 */
@Slf4j
@Service
public class OrderServiceImpl implements OrderService {

    /** 防重 Token Redis key 前缀 */
    public static final String ORDER_TOKEN_KEY_PREFIX = "mall:order:token:";

    /** 订单状态：0待支付 1已支付 2已发货 3已完成 4已取消 */
    private static final int STATUS_UNPAID = 0;
    private static final int STATUS_PAID = 1;
    private static final int STATUS_CANCELLED = 4;

    /** 商品上架状态 */
    private static final int STATUS_ON = 1;

    private static final DateTimeFormatter ORDER_NO_FORMAT = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    private final OrderMapper orderMapper;
    private final OrderItemMapper orderItemMapper;
    private final SkuMapper skuMapper;
    private final ProductMapper productMapper;
    private final AddressMapper addressMapper;
    private final CartService cartService;
    private final OrderCloseProducer orderCloseProducer;
    private final StringRedisTemplate stringRedisTemplate;
    private final ObjectMapper objectMapper;

    private final long tokenTtlMinutes;

    public OrderServiceImpl(OrderMapper orderMapper, OrderItemMapper orderItemMapper,
                            SkuMapper skuMapper, ProductMapper productMapper,
                            AddressMapper addressMapper, CartService cartService,
                            OrderCloseProducer orderCloseProducer,
                            StringRedisTemplate stringRedisTemplate, ObjectMapper objectMapper,
                            @org.springframework.beans.factory.annotation.Value("${mall.order.token-ttl-minutes:10}")
                            long tokenTtlMinutes) {
        this.orderMapper = orderMapper;
        this.orderItemMapper = orderItemMapper;
        this.skuMapper = skuMapper;
        this.productMapper = productMapper;
        this.addressMapper = addressMapper;
        this.cartService = cartService;
        this.orderCloseProducer = orderCloseProducer;
        this.stringRedisTemplate = stringRedisTemplate;
        this.objectMapper = objectMapper;
        this.tokenTtlMinutes = tokenTtlMinutes;
    }

    @Override
    public String getOrderToken() {
        Long userId = currentUserId();
        String token = UUID.randomUUID().toString().replace("-", "");
        stringRedisTemplate.opsForValue().set(tokenKey(token), String.valueOf(userId),
                Duration.ofMinutes(tokenTtlMinutes));
        return token;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public CreateOrderVO createOrder(CreateOrderDTO dto) {
        Long userId = currentUserId();
        // 1. 原子消费防重 Token（一次性），重复提交直接拒绝
        consumeToken(dto.getToken(), userId);

        // 2. 校验收货地址归属
        UmsAddress address = addressMapper.selectById(dto.getAddressId());
        if (address == null || !address.getUserId().equals(userId)) {
            throw new BizException("收货地址不存在");
        }

        // 3. 合并同 SKU 条目（避免同 SKU 拆成多条分别扣库存绕过库存校验），并校验商品
        Map<Long, Integer> mergedItems = mergeItems(dto.getItems());
        Map<Long, PmsSku> skuMap = new LinkedHashMap<>(mergedItems.size());
        BigDecimal totalAmount = BigDecimal.ZERO;
        List<OmsOrderItem> orderItems = new ArrayList<>(mergedItems.size());
        for (Map.Entry<Long, Integer> entry : mergedItems.entrySet()) {
            Long skuId = entry.getKey();
            int quantity = entry.getValue();
            PmsSku sku = requireValidSku(skuId);

            // 条件扣减库存：影响行数为 0 表示库存不足（数据库原子性防超卖）
            int deducted = skuMapper.deductStock(skuId, quantity);
            if (deducted == 0) {
                throw new BizException("库存不足，请调整购买数量");
            }

            PmsProduct product = productMapper.selectById(sku.getProductId());
            OmsOrderItem item = new OmsOrderItem();
            item.setSkuId(skuId);
            item.setProductId(sku.getProductId());
            item.setProductName(product.getName());
            item.setSkuSpec(toSpecText(sku));
            item.setPrice(sku.getPrice());
            item.setQuantity(quantity);
            orderItems.add(item);
            skuMap.put(skuId, sku);

            totalAmount = totalAmount.add(sku.getPrice().multiply(BigDecimal.valueOf(quantity)));
        }

        // 4. 生成订单与订单项（下单快照：商品名/规格/单价/地址）
        OmsOrder order = new OmsOrder();
        order.setOrderNo(generateOrderNo());
        order.setUserId(userId);
        order.setTotalAmount(totalAmount);
        order.setStatus(STATUS_UNPAID);
        order.setAddressSnapshot(buildAddressSnapshot(address));
        orderMapper.insert(order);

        orderItems.forEach(item -> item.setOrderId(order.getId()));
        orderItems.forEach(orderItemMapper::insert);

        // 5. 事务提交后再执行副作用：发送延迟关单消息 + 清购物车已购项
        List<Long> purchasedSkuIds = new ArrayList<>(mergedItems.keySet());
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                orderCloseProducer.sendDelayClose(order.getOrderNo());
                purchasedSkuIds.forEach(cartService::remove);
            }
        });

        log.info("下单成功: userId={}, orderNo={}, orderItems={}, totalAmount={}",
                userId, order.getOrderNo(), orderItems.size(), totalAmount);
        return new CreateOrderVO(order.getId(), order.getOrderNo(), totalAmount);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void payOrder(String orderNo) {
        // 条件更新保证幂等：仅待支付 → 已支付，重复回调影响行数为 0
        int updated = orderMapper.update(null, new LambdaUpdateWrapper<OmsOrder>()
                .eq(OmsOrder::getOrderNo, orderNo)
                .eq(OmsOrder::getStatus, STATUS_UNPAID)
                .set(OmsOrder::getStatus, STATUS_PAID)
                .set(OmsOrder::getPayTime, LocalDateTime.now()));
        if (updated == 0) {
            OmsOrder order = requireOrderByNo(orderNo);
            if (order.getStatus() == STATUS_PAID) {
                // 幂等：已支付直接返回成功
                log.info("重复支付回调，幂等处理: orderNo={}", orderNo);
                return;
            }
            throw new BizException("当前订单状态不可支付");
        }
        // 支付成功后累加商品销量
        OmsOrder order = requireOrderByNo(orderNo);
        for (OmsOrderItem item : listItems(order.getId())) {
            productMapper.increaseSales(item.getProductId(), item.getQuantity());
        }
        log.info("订单支付成功: orderNo={}", orderNo);
    }

    @Override
    public void cancelOrder(String orderNo) {
        Long userId = currentUserId();
        OmsOrder order = requireOrderByNo(orderNo);
        if (!order.getUserId().equals(userId)) {
            // 他人订单按不存在处理，防越权
            throw new BizException("订单不存在");
        }
        if (!tryCancel(order)) {
            OmsOrder latest = requireOrderByNo(orderNo);
            if (latest.getStatus() == STATUS_CANCELLED) {
                // 幂等：已取消直接返回
                log.info("订单已取消，幂等处理: orderNo={}", orderNo);
                return;
            }
            throw new BizException("当前订单状态不可取消");
        }
        log.info("订单主动取消成功并回补库存: orderNo={}", orderNo);
    }

    @Override
    public void closeTimeoutOrder(String orderNo) {
        OmsOrder order = orderMapper.selectOne(new LambdaQueryWrapper<OmsOrder>()
                .eq(OmsOrder::getOrderNo, orderNo));
        if (order == null) {
            // 下单事务回滚等场景可能消费到无对应订单的消息，确认消费避免无限重试
            log.warn("超时关单订单不存在，跳过: orderNo={}", orderNo);
            return;
        }
        // 已支付/已取消等非待支付状态条件更新影响 0 行，静默跳过（消费幂等）
        if (tryCancel(order)) {
            log.info("订单超时关单成功并回补库存: orderNo={}", orderNo);
        } else {
            log.info("订单已非待支付状态，跳过超时关单: orderNo={}, status={}", orderNo, order.getStatus());
        }
    }

    @Override
    public PageResult<OrderVO> pageMyOrders(OrderPageQueryDTO query) {
        Long userId = currentUserId();
        Page<OmsOrder> page = new Page<>(Math.max(query.getPageNum(), 1),
                Math.min(Math.max(query.getPageSize(), 1), 50));
        LambdaQueryWrapper<OmsOrder> wrapper = new LambdaQueryWrapper<OmsOrder>()
                .eq(OmsOrder::getUserId, userId)
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
    public OrderVO detail(String orderNo) {
        Long userId = currentUserId();
        OmsOrder order = orderMapper.selectOne(new LambdaQueryWrapper<OmsOrder>()
                .eq(OmsOrder::getOrderNo, orderNo));
        if (order == null || !order.getUserId().equals(userId)) {
            throw new BizException("订单不存在");
        }
        return toVO(order, loadItemsGrouped(List.of(order.getId())).getOrDefault(order.getId(), List.of()));
    }

    // ---------------- 内部方法 ----------------

    /**
     * 尝试取消订单：条件更新（仅 0待支付 → 4已取消）保证与支付/关单并发时只有一方生效。
     * 取消成功后回补库存并返回 true；影响行数为 0（已支付/已取消等）返回 false。
     */
    private boolean tryCancel(OmsOrder order) {
        int updated = orderMapper.update(null, new LambdaUpdateWrapper<OmsOrder>()
                .eq(OmsOrder::getId, order.getId())
                .eq(OmsOrder::getStatus, STATUS_UNPAID)
                .set(OmsOrder::getStatus, STATUS_CANCELLED));
        if (updated == 1) {
            restock(order.getId());
            return true;
        }
        return false;
    }

    /** 回补订单各 SKU 库存（仅在条件取消更新成功后调用一次） */
    private void restock(Long orderId) {
        for (OmsOrderItem item : listItems(orderId)) {
            skuMapper.restockStock(item.getSkuId(), item.getQuantity());
        }
    }

    /** 原子消费防重 Token：值不匹配/不存在/已被消费均拒绝 */
    private void consumeToken(String token, Long userId) {
        String key = tokenKey(token);
        String value = stringRedisTemplate.opsForValue().get(key);
        if (value == null || !value.equals(String.valueOf(userId))) {
            throw new BizException("页面已过期或订单正在提交，请刷新后重试");
        }
        // DELETE 返回是否删除成功：并发重复提交时仅一个请求能删掉
        Boolean deleted = stringRedisTemplate.delete(key);
        if (!Boolean.TRUE.equals(deleted)) {
            throw new BizException("请勿重复提交订单");
        }
    }

    /** 合并同 SKU 购买数量，保持原顺序 */
    private Map<Long, Integer> mergeItems(List<CreateOrderDTO.CreateOrderItemDTO> items) {
        Map<Long, Integer> merged = new LinkedHashMap<>();
        for (CreateOrderDTO.CreateOrderItemDTO item : items) {
            merged.merge(item.getSkuId(), item.getQuantity(), Integer::sum);
        }
        return merged;
    }

    /** 校验并返回 SKU：不存在/商品下架抛业务异常 */
    private PmsSku requireValidSku(Long skuId) {
        PmsSku sku = skuMapper.selectById(skuId);
        if (sku == null) {
            throw new BizException("商品规格不存在");
        }
        PmsProduct product = productMapper.selectById(sku.getProductId());
        if (product == null || !Integer.valueOf(STATUS_ON).equals(product.getStatus())) {
            throw new BizException("商品已下架或不存在");
        }
        return sku;
    }

    private OmsOrder requireOrderByNo(String orderNo) {
        OmsOrder order = orderMapper.selectOne(new LambdaQueryWrapper<OmsOrder>()
                .eq(OmsOrder::getOrderNo, orderNo));
        if (order == null) {
            throw new BizException("订单不存在");
        }
        return order;
    }

    private List<OmsOrderItem> listItems(Long orderId) {
        return orderItemMapper.selectList(new LambdaQueryWrapper<OmsOrderItem>()
                .eq(OmsOrderItem::getOrderId, orderId));
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

    /** 收货地址快照 JSON（与前端 OrderDetailView 解析的 6 字段契约一致） */
    private String buildAddressSnapshot(UmsAddress address) {
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
            log.error("收货地址快照序列化失败: addressId={}", address.getId(), e);
            throw new BizException("下单失败，请稍后重试");
        }
    }

    /**
     * SKU 规格解析为展示文案（值以 " / " 拼接，与购物车/前端详情页约定一致），
     * 无规格/规格JSON损坏时回退 skuCode。
     */
    private String toSpecText(PmsSku sku) {
        if (!StringUtils.hasText(sku.getSpecJson())) {
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

    /** 订单编号：时间戳 + 6位随机数（唯一索引兜底） */
    private String generateOrderNo() {
        return ORDER_NO_FORMAT.format(LocalDateTime.now())
                + ThreadLocalRandom.current().nextInt(100000, 1000000);
    }

    private String tokenKey(String token) {
        return ORDER_TOKEN_KEY_PREFIX + token;
    }

    private Long currentUserId() {
        Long userId = LoginUser.getCurrentUserId();
        if (userId == null) {
            throw new BizException("未登录");
        }
        return userId;
    }
}
