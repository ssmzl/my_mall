package com.mall.cart.service.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mall.cart.dto.CartItemDTO;
import com.mall.cart.dto.CartItemSnapshot;
import com.mall.cart.dto.CartItemVO;
import com.mall.cart.dto.CartSelectedDTO;
import com.mall.cart.dto.CartVO;
import com.mall.cart.service.CartService;
import com.mall.common.BizException;
import com.mall.product.entity.PmsProduct;
import com.mall.product.entity.PmsSku;
import com.mall.product.mapper.ProductMapper;
import com.mall.product.mapper.SkuMapper;
import com.mall.security.LoginUser;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 购物车服务实现（Redis Hash 存储）。
 *
 * <p>存储结构：key = mall:cart:{userId}，field = skuId，value = 条目快照 JSON（含商品名、
 * 规格、主图、加购时单价、数量、勾选状态、加购时间）。加购时固化商品快照，展示不反查商品库。
 * 数量/勾选变更读改写回同一快照；合计金额与件数在查询时聚合计算。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CartServiceImpl implements CartService {

    /** 购物车 Redis key 前缀 */
    public static final String CART_KEY_PREFIX = "mall:cart:";

    private static final int STATUS_ON = 1;

    private final StringRedisTemplate stringRedisTemplate;
    private final SkuMapper skuMapper;
    private final ProductMapper productMapper;
    private final ObjectMapper objectMapper;

    @Override
    public CartVO getCart() {
        String key = cartKey();
        HashOperations<String, Object, Object> hashOps = hashOps();
        Map<Object, Object> entries = hashOps.entries(key);

        List<CartItemSnapshot> snapshots = new ArrayList<>(entries.size());
        for (Map.Entry<Object, Object> entry : entries.entrySet()) {
            CartItemSnapshot snapshot = readSnapshot(hashOps, key, entry.getKey(), entry.getValue());
            if (snapshot != null) {
                snapshots.add(snapshot);
            }
        }
        // 先加入的排前，同毫秒加入按 skuId 稳定排序
        snapshots.sort(Comparator.comparingLong(CartItemSnapshot::getAddTime)
                .thenComparingLong(CartItemSnapshot::getSkuId));

        CartVO vo = new CartVO();
        vo.setItems(snapshots.stream().map(this::toVO).collect(Collectors.toList()));
        vo.setTotalCount(snapshots.stream().mapToInt(CartItemSnapshot::getQuantity).sum());
        vo.setSelectedCount(snapshots.stream()
                .filter(s -> Boolean.TRUE.equals(s.getSelected()))
                .mapToInt(CartItemSnapshot::getQuantity).sum());
        vo.setSelectedAmount(snapshots.stream()
                .filter(s -> Boolean.TRUE.equals(s.getSelected()))
                .map(s -> s.getPrice().multiply(BigDecimal.valueOf(s.getQuantity())))
                .reduce(BigDecimal.ZERO, BigDecimal::add));
        return vo;
    }

    @Override
    public void add(CartItemDTO dto) {
        PmsSku sku = requireSku(dto.getSkuId());
        PmsProduct product = requireOnSaleProduct(sku);

        String key = cartKey();
        String field = String.valueOf(dto.getSkuId());
        HashOperations<String, Object, Object> hashOps = hashOps();
        CartItemSnapshot existing = readSnapshot(hashOps, key, field, hashOps.get(key, field));

        int newQuantity = dto.getQuantity();
        CartItemSnapshot snapshot;
        if (existing != null) {
            // 已在购物车：累加数量，保留原勾选状态与加购时间
            newQuantity += existing.getQuantity();
            snapshot = existing;
        } else {
            snapshot = buildSnapshot(sku, product);
            snapshot.setAddTime(System.currentTimeMillis());
            snapshot.setSelected(true);
        }
        if (newQuantity > sku.getStock()) {
            throw new BizException("库存不足，该商品最多可购买 " + sku.getStock() + " 件");
        }
        snapshot.setQuantity(newQuantity);
        hashOps.put(key, field, serialize(snapshot));
    }

    @Override
    public void updateQuantity(CartItemDTO dto) {
        PmsSku sku = requireSku(dto.getSkuId());

        String key = cartKey();
        String field = String.valueOf(dto.getSkuId());
        HashOperations<String, Object, Object> hashOps = hashOps();
        CartItemSnapshot existing = readSnapshot(hashOps, key, field, hashOps.get(key, field));
        if (existing == null) {
            throw new BizException("购物车中不存在该商品");
        }
        if (dto.getQuantity() > sku.getStock()) {
            throw new BizException("库存不足，该商品最多可购买 " + sku.getStock() + " 件");
        }
        existing.setQuantity(dto.getQuantity());
        hashOps.put(key, field, serialize(existing));
    }

    @Override
    public void updateSelected(CartSelectedDTO dto) {
        String key = cartKey();
        String field = String.valueOf(dto.getSkuId());
        HashOperations<String, Object, Object> hashOps = hashOps();
        CartItemSnapshot existing = readSnapshot(hashOps, key, field, hashOps.get(key, field));
        if (existing == null) {
            throw new BizException("购物车中不存在该商品");
        }
        existing.setSelected(dto.getSelected());
        hashOps.put(key, field, serialize(existing));
    }

    @Override
    public void updateSelectedAll(boolean selected) {
        String key = cartKey();
        HashOperations<String, Object, Object> hashOps = hashOps();
        Map<Object, Object> entries = hashOps.entries(key);
        if (entries.isEmpty()) {
            return;
        }
        Map<Object, Object> updated = new HashMap<>(entries.size());
        for (Map.Entry<Object, Object> entry : entries.entrySet()) {
            CartItemSnapshot snapshot = readSnapshot(hashOps, key, entry.getKey(), entry.getValue());
            if (snapshot == null) {
                continue;
            }
            snapshot.setSelected(selected);
            updated.put(entry.getKey(), serialize(snapshot));
        }
        if (!updated.isEmpty()) {
            hashOps.putAll(key, updated);
        }
    }

    @Override
    public void remove(Long skuId) {
        // 删除幂等：条目不存在时 Redis HDEL 无副作用
        hashOps().delete(cartKey(), String.valueOf(skuId));
    }

    // ---------------- 内部方法 ----------------

    /** 校验并返回 SKU，不存在抛业务异常 */
    private PmsSku requireSku(Long skuId) {
        PmsSku sku = skuMapper.selectById(skuId);
        if (sku == null) {
            throw new BizException("商品规格不存在");
        }
        return sku;
    }

    /** 校验并返回上架商品，不存在/已下架抛业务异常 */
    private PmsProduct requireOnSaleProduct(PmsSku sku) {
        PmsProduct product = productMapper.selectById(sku.getProductId());
        if (product == null || !Integer.valueOf(STATUS_ON).equals(product.getStatus())) {
            throw new BizException("商品已下架或不存在");
        }
        return product;
    }

    /**
     * 读取并反序列化条目快照。JSON 损坏时记录诊断信息、删除脏数据并按条目不存在处理，
     * 避免单条脏数据导致整个购物车不可用（购物车无 TTL，脏数据不会自愈）。
     */
    private CartItemSnapshot readSnapshot(HashOperations<String, Object, Object> hashOps,
                                          String key, Object field, Object value) {
        if (value == null) {
            return null;
        }
        try {
            return objectMapper.readValue((String) value, CartItemSnapshot.class);
        } catch (JsonProcessingException e) {
            log.error("购物车条目反序列化失败，删除脏数据: userId={}, field={}, value={}, error={}",
                    LoginUser.getCurrentUserId(), field, value, e.getMessage());
            hashOps.delete(key, field);
            return null;
        }
    }

    /** 加购时构建条目快照（固化商品信息与价格） */
    private CartItemSnapshot buildSnapshot(PmsSku sku, PmsProduct product) {
        CartItemSnapshot snapshot = new CartItemSnapshot();
        snapshot.setSkuId(sku.getId());
        snapshot.setProductId(product.getId());
        snapshot.setProductName(product.getName());
        snapshot.setSkuSpec(toSpecText(sku));
        snapshot.setMainPic(product.getMainPic());
        snapshot.setPrice(sku.getPrice());
        snapshot.setQuantity(0);
        return snapshot;
    }

    /**
     * 将 SKU 规格解析为展示文案（值以 " / " 拼接，与前端商品详情页展示约定一致）。
     * 无规格/规格JSON损坏时回退 skuCode，不阻断加购。
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

    private CartItemVO toVO(CartItemSnapshot snapshot) {
        CartItemVO vo = new CartItemVO();
        vo.setSkuId(snapshot.getSkuId());
        vo.setProductId(snapshot.getProductId());
        vo.setProductName(snapshot.getProductName());
        vo.setSkuSpec(snapshot.getSkuSpec());
        vo.setMainPic(snapshot.getMainPic());
        vo.setPrice(snapshot.getPrice());
        vo.setQuantity(snapshot.getQuantity());
        vo.setSelected(snapshot.getSelected());
        vo.setSubtotal(snapshot.getPrice().multiply(BigDecimal.valueOf(snapshot.getQuantity())));
        return vo;
    }

    private String serialize(CartItemSnapshot snapshot) {
        try {
            return objectMapper.writeValueAsString(snapshot);
        } catch (JsonProcessingException e) {
            log.error("购物车条目序列化失败: snapshot={}", snapshot, e);
            throw new BizException("购物车数据处理失败，请稍后重试");
        }
    }

    private String cartKey() {
        Long userId = LoginUser.getCurrentUserId();
        if (userId == null) {
            throw new BizException("未登录");
        }
        return CART_KEY_PREFIX + userId;
    }

    private HashOperations<String, Object, Object> hashOps() {
        return stringRedisTemplate.opsForHash();
    }
}
