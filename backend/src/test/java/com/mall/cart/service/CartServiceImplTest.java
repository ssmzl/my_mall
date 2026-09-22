package com.mall.cart.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mall.cart.dto.CartItemDTO;
import com.mall.cart.dto.CartItemSnapshot;
import com.mall.cart.dto.CartSelectedDTO;
import com.mall.cart.dto.CartVO;
import com.mall.cart.service.impl.CartServiceImpl;
import com.mall.common.BizException;
import com.mall.product.entity.PmsProduct;
import com.mall.product.entity.PmsSku;
import com.mall.product.mapper.ProductMapper;
import com.mall.product.mapper.SkuMapper;
import com.mall.security.LoginUser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 购物车服务单元测试：覆盖加购合并/库存校验、数量/勾选变更、全选、聚合合计、脏数据处理。
 * mock StringRedisTemplate 的 HashOperations 与商品相关 Mapper。
 */
@ExtendWith(MockitoExtension.class)
class CartServiceImplTest {

    private static final Long USER_ID = 1L;
    private static final Long SKU_ID = 1001L;
    private static final Long SKU_ID_2 = 1002L;
    private static final Long PRODUCT_ID = 100L;
    private static final String CART_KEY = CartServiceImpl.CART_KEY_PREFIX + USER_ID;
    private static final String FIELD = String.valueOf(SKU_ID);

    @Mock
    private StringRedisTemplate stringRedisTemplate;

    @Mock
    private HashOperations<String, Object, Object> hashOperations;

    @Mock
    private SkuMapper skuMapper;

    @Mock
    private ProductMapper productMapper;

    @Captor
    private ArgumentCaptor<String> jsonCaptor;

    @Captor
    private ArgumentCaptor<Map<Object, Object>> putAllCaptor;

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    private CartServiceImpl cartService;

    @BeforeEach
    void setUp() {
        cartService = new CartServiceImpl(stringRedisTemplate, skuMapper, productMapper, objectMapper);
        LoginUser.set(new LoginUser(USER_ID, "testuser", 0));
        lenient().doReturn(hashOperations).when(stringRedisTemplate).opsForHash();
    }

    @AfterEach
    void tearDown() {
        LoginUser.remove();
    }

    // ---------------- 加入购物车 ----------------

    @Test
    void addShouldStoreNewSnapshotWithProductInfo() throws Exception {
        when(skuMapper.selectById(SKU_ID)).thenReturn(buildSku());
        when(productMapper.selectById(PRODUCT_ID)).thenReturn(buildProduct());
        when(hashOperations.get(CART_KEY, FIELD)).thenReturn(null);

        cartService.add(dto(SKU_ID, 1));

        verify(hashOperations).put(eq(CART_KEY), eq(FIELD), jsonCaptor.capture());
        CartItemSnapshot stored = objectMapper.readValue(jsonCaptor.getValue(), CartItemSnapshot.class);
        assertThat(stored.getSkuId()).isEqualTo(SKU_ID);
        assertThat(stored.getProductId()).isEqualTo(PRODUCT_ID);
        assertThat(stored.getProductName()).isEqualTo("测试手机");
        assertThat(stored.getMainPic()).isEqualTo("https://cdn.example.com/p100.jpg");
        assertThat(stored.getPrice()).isEqualByComparingTo(new BigDecimal("3999.00"));
        // 规格JSON解析为值拼接文案（与前端详情页展示约定一致）
        assertThat(stored.getSkuSpec()).isEqualTo("黑色 / 256GB");
        assertThat(stored.getQuantity()).isEqualTo(1);
        // 新加入条目默认勾选
        assertThat(stored.getSelected()).isTrue();
        assertThat(stored.getAddTime()).isPositive();
    }

    @Test
    void addShouldMergeQuantityWhenSkuAlreadyInCart() throws Exception {
        when(skuMapper.selectById(SKU_ID)).thenReturn(buildSku());
        when(productMapper.selectById(PRODUCT_ID)).thenReturn(buildProduct());
        CartItemSnapshot existing = snapshot(SKU_ID, 2, false, 1000L);
        when(hashOperations.get(CART_KEY, FIELD)).thenReturn(objectMapper.writeValueAsString(existing));

        cartService.add(dto(SKU_ID, 1));

        verify(hashOperations).put(eq(CART_KEY), eq(FIELD), jsonCaptor.capture());
        CartItemSnapshot stored = objectMapper.readValue(jsonCaptor.getValue(), CartItemSnapshot.class);
        // 数量累加为 3；保留原勾选状态与加购时间
        assertThat(stored.getQuantity()).isEqualTo(3);
        assertThat(stored.getSelected()).isFalse();
        assertThat(stored.getAddTime()).isEqualTo(1000L);
    }

    @Test
    void addShouldThrowWhenExceedStockAndNotWrite() throws Exception {
        when(skuMapper.selectById(SKU_ID)).thenReturn(buildSku());
        when(productMapper.selectById(PRODUCT_ID)).thenReturn(buildProduct());
        // 已有 2 件，库存 5，再加 4 件超库存
        when(hashOperations.get(CART_KEY, FIELD))
                .thenReturn(objectMapper.writeValueAsString(snapshot(SKU_ID, 2, true, 1000L)));

        assertThatThrownBy(() -> cartService.add(dto(SKU_ID, 4)))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("库存不足");

        verify(hashOperations, never()).put(anyString(), any(), any());
    }

    @Test
    void addShouldThrowWhenSkuNotFound() {
        when(skuMapper.selectById(SKU_ID)).thenReturn(null);

        assertThatThrownBy(() -> cartService.add(dto(SKU_ID, 1)))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("规格不存在");

        verify(productMapper, never()).selectById(any());
    }

    @Test
    void addShouldThrowWhenProductOffShelf() {
        when(skuMapper.selectById(SKU_ID)).thenReturn(buildSku());
        PmsProduct offShelf = buildProduct();
        offShelf.setStatus(0);
        when(productMapper.selectById(PRODUCT_ID)).thenReturn(offShelf);

        assertThatThrownBy(() -> cartService.add(dto(SKU_ID, 1)))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("已下架");

        verify(hashOperations, never()).put(anyString(), any(), any());
    }

    // ---------------- 修改数量 ----------------

    @Test
    void updateQuantityShouldUpdateSnapshot() throws Exception {
        when(skuMapper.selectById(SKU_ID)).thenReturn(buildSku());
        when(hashOperations.get(CART_KEY, FIELD))
                .thenReturn(objectMapper.writeValueAsString(snapshot(SKU_ID, 2, true, 1000L)));

        cartService.updateQuantity(dto(SKU_ID, 4));

        verify(hashOperations).put(eq(CART_KEY), eq(FIELD), jsonCaptor.capture());
        assertThat(objectMapper.readValue(jsonCaptor.getValue(), CartItemSnapshot.class).getQuantity())
                .isEqualTo(4);
    }

    @Test
    void updateQuantityShouldThrowWhenEntryMissing() {
        when(skuMapper.selectById(SKU_ID)).thenReturn(buildSku());
        when(hashOperations.get(CART_KEY, FIELD)).thenReturn(null);

        assertThatThrownBy(() -> cartService.updateQuantity(dto(SKU_ID, 4)))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("不存在");
    }

    @Test
    void updateQuantityShouldThrowWhenExceedStock() throws Exception {
        when(skuMapper.selectById(SKU_ID)).thenReturn(buildSku());
        when(hashOperations.get(CART_KEY, FIELD))
                .thenReturn(objectMapper.writeValueAsString(snapshot(SKU_ID, 2, true, 1000L)));

        // 库存 5，改到 6 超库存
        assertThatThrownBy(() -> cartService.updateQuantity(dto(SKU_ID, 6)))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("库存不足");
    }

    // ---------------- 勾选 ----------------

    @Test
    void updateSelectedShouldToggleSnapshot() throws Exception {
        when(hashOperations.get(CART_KEY, FIELD))
                .thenReturn(objectMapper.writeValueAsString(snapshot(SKU_ID, 2, false, 1000L)));

        cartService.updateSelected(new CartSelectedDTO(SKU_ID, true));

        verify(hashOperations).put(eq(CART_KEY), eq(FIELD), jsonCaptor.capture());
        assertThat(objectMapper.readValue(jsonCaptor.getValue(), CartItemSnapshot.class).getSelected()).isTrue();
    }

    @Test
    void updateSelectedShouldThrowWhenEntryMissing() {
        when(hashOperations.get(CART_KEY, FIELD)).thenReturn(null);

        assertThatThrownBy(() -> cartService.updateSelected(new CartSelectedDTO(SKU_ID, true)))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("不存在");
    }

    @Test
    void updateSelectedAllShouldUpdateAllEntries() throws Exception {
        Map<Object, Object> entries = new LinkedHashMap<>();
        entries.put(String.valueOf(SKU_ID), objectMapper.writeValueAsString(snapshot(SKU_ID, 2, false, 1000L)));
        entries.put(String.valueOf(SKU_ID_2), objectMapper.writeValueAsString(snapshot(SKU_ID_2, 3, false, 2000L)));
        when(hashOperations.entries(CART_KEY)).thenReturn(entries);

        cartService.updateSelectedAll(true);

        verify(hashOperations).putAll(eq(CART_KEY), putAllCaptor.capture());
        Map<Object, Object> updated = putAllCaptor.getValue();
        assertThat(updated).hasSize(2);
        for (Object value : updated.values()) {
            assertThat(objectMapper.readValue((String) value, CartItemSnapshot.class).getSelected()).isTrue();
        }
    }

    // ---------------- 删除 ----------------

    @Test
    void removeShouldDeleteField() {
        cartService.remove(SKU_ID);

        verify(hashOperations).delete(CART_KEY, FIELD);
    }

    // ---------------- 查询聚合 ----------------

    @Test
    void getCartShouldAggregateTotalsAndSortByAddTime() throws Exception {
        Map<Object, Object> entries = new LinkedHashMap<>();
        // 后加入的条目放在前面，验证按 addTime 升序输出
        entries.put(String.valueOf(SKU_ID_2), objectMapper.writeValueAsString(snapshot(SKU_ID_2, 3, false, 2000L)));
        entries.put(String.valueOf(SKU_ID), objectMapper.writeValueAsString(snapshot(SKU_ID, 2, true, 1000L)));
        when(hashOperations.entries(CART_KEY)).thenReturn(entries);

        CartVO vo = cartService.getCart();

        assertThat(vo.getItems()).hasSize(2);
        // 总件数 = 2 + 3 = 5；勾选件数 = 2；勾选合计 = 100.00 * 2 = 200.00
        assertThat(vo.getTotalCount()).isEqualTo(5);
        assertThat(vo.getSelectedCount()).isEqualTo(2);
        assertThat(vo.getSelectedAmount()).isEqualByComparingTo(new BigDecimal("200.00"));
        // 按加购时间升序：1001 在前
        assertThat(vo.getItems().get(0).getSkuId()).isEqualTo(SKU_ID);
        assertThat(vo.getItems().get(1).getSkuId()).isEqualTo(SKU_ID_2);
        // 小计 = price * quantity
        assertThat(vo.getItems().get(0).getSubtotal()).isEqualByComparingTo(new BigDecimal("200.00"));
        assertThat(vo.getItems().get(1).getSubtotal()).isEqualByComparingTo(new BigDecimal("300.00"));
    }

    @Test
    void getCartShouldReturnEmptyVoWhenNoEntries() {
        when(hashOperations.entries(CART_KEY)).thenReturn(Map.of());

        CartVO vo = cartService.getCart();

        assertThat(vo.getItems()).isEmpty();
        assertThat(vo.getTotalCount()).isZero();
        assertThat(vo.getSelectedCount()).isZero();
        assertThat(vo.getSelectedAmount()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    void getCartShouldSkipAndDeleteCorruptEntry() throws Exception {
        Map<Object, Object> entries = new LinkedHashMap<>();
        entries.put(String.valueOf(SKU_ID), "not-a-json");
        entries.put(String.valueOf(SKU_ID_2), objectMapper.writeValueAsString(snapshot(SKU_ID_2, 3, true, 2000L)));
        when(hashOperations.entries(CART_KEY)).thenReturn(entries);

        CartVO vo = cartService.getCart();

        // 脏数据被跳过并删除，不影响其余条目与合计
        assertThat(vo.getItems()).hasSize(1);
        assertThat(vo.getItems().get(0).getSkuId()).isEqualTo(SKU_ID_2);
        assertThat(vo.getSelectedAmount()).isEqualByComparingTo(new BigDecimal("300.00"));
        verify(hashOperations).delete(CART_KEY, String.valueOf(SKU_ID));
    }

    // ---------------- 测试数据构造 ----------------

    private CartItemDTO dto(Long skuId, Integer quantity) {
        CartItemDTO dto = new CartItemDTO();
        dto.setSkuId(skuId);
        dto.setQuantity(quantity);
        return dto;
    }

    private CartItemSnapshot snapshot(Long skuId, int quantity, boolean selected, long addTime) {
        CartItemSnapshot snapshot = new CartItemSnapshot();
        snapshot.setSkuId(skuId);
        snapshot.setProductId(PRODUCT_ID);
        snapshot.setProductName("测试手机");
        snapshot.setSkuSpec("黑色 / 256GB");
        snapshot.setMainPic("https://cdn.example.com/p100.jpg");
        snapshot.setPrice(new BigDecimal("100.00"));
        snapshot.setQuantity(quantity);
        snapshot.setSelected(selected);
        snapshot.setAddTime(addTime);
        return snapshot;
    }

    private PmsSku buildSku() {
        PmsSku sku = new PmsSku();
        sku.setId(SKU_ID);
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
        product.setMainPic("https://cdn.example.com/p100.jpg");
        product.setPrice(new BigDecimal("3999.00"));
        product.setStock(50);
        product.setSales(120);
        product.setStatus(1); // 上架
        return product;
    }
}
