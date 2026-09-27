package com.mall.product.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mall.common.BizException;
import com.mall.common.PageResult;
import com.mall.product.cache.CategoryTreeCache;
import com.mall.product.dto.ProductDetailVO;
import com.mall.product.entity.PmsCategory;
import com.mall.product.entity.PmsProduct;
import com.mall.product.entity.PmsSku;
import com.mall.product.mapper.CategoryMapper;
import com.mall.product.mapper.ProductMapper;
import com.mall.product.mapper.SkuMapper;
import com.mall.product.service.impl.ProductServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 商品服务单元测试：重点覆盖商品详情缓存三防逻辑
 * （空值缓存防穿透 / Redisson 互斥锁防击穿 / 随机过期防雪崩），mock RedisTemplate 与 Redisson。
 */
@ExtendWith(MockitoExtension.class)
class ProductServiceImplTest {

    private static final Long PRODUCT_ID = 100L;
    private static final String CACHE_KEY = ProductServiceImpl.CACHE_KEY_PREFIX + PRODUCT_ID;
    private static final String LOCK_KEY = ProductServiceImpl.LOCK_KEY_PREFIX + PRODUCT_ID;

    @Mock
    private CategoryMapper categoryMapper;

    @Mock
    private ProductMapper productMapper;

    @Mock
    private SkuMapper skuMapper;

    @Mock
    private StringRedisTemplate stringRedisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    @Mock
    private RedissonClient redissonClient;

    @Mock
    private RLock lock;

    @Captor
    private ArgumentCaptor<Wrapper<PmsProduct>> wrapperCaptor;

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    private ProductServiceImpl productService;

    @BeforeEach
    void setUp() {
        // 初始化 MyBatis-Plus 元数据：page() 的 select(Class, Predicate) 与分类展开的 lambda 条件依赖 TableInfo
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, PmsProduct.class);
        TableInfoHelper.initTableInfo(assistant, PmsCategory.class);
        productService = new ProductServiceImpl(
                categoryMapper, productMapper, skuMapper, stringRedisTemplate, redissonClient, objectMapper,
                new CategoryTreeCache(stringRedisTemplate, objectMapper));
        // opsForValue 在部分测试（如分类树）中不会被调用，用 lenient 避免 UnnecessaryStubbing
        lenient().when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
    }

    // ---------------- 商品详情：缓存命中 ----------------

    @Test
    void getDetailShouldReturnCachedValueWithoutQueryingDb() throws Exception {
        when(valueOperations.get(CACHE_KEY)).thenReturn(objectMapper.writeValueAsString(buildDetail()));

        ProductDetailVO vo = productService.getDetail(PRODUCT_ID);

        assertThat(vo.getId()).isEqualTo(PRODUCT_ID);
        assertThat(vo.getName()).isEqualTo("测试手机");
        assertThat(vo.getSkus()).hasSize(1);
        // 缓存命中：不查库、不碰锁、不写缓存
        verify(productMapper, never()).selectById(anyLong());
        verify(redissonClient, never()).getLock(anyString());
        verify(valueOperations, never()).set(anyString(), anyString(), any(Duration.class));
    }

    @Test
    void getDetailShouldThrowWhenNullValueCachedAndNotQueryDb() {
        // 空值缓存命中（防穿透）：直接拒绝，不回源数据库
        when(valueOperations.get(CACHE_KEY)).thenReturn(ProductServiceImpl.NULL_VALUE);

        assertThatThrownBy(() -> productService.getDetail(PRODUCT_ID))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("不存在");

        verify(productMapper, never()).selectById(anyLong());
    }

    // ---------------- 商品详情：缓存重建（防击穿 + 防雪崩） ----------------

    @Test
    void getDetailShouldRebuildCacheWithRandomTtlOnMiss() throws Exception {
        // 第一次读缓存未命中，双重检查仍未命中
        when(valueOperations.get(CACHE_KEY)).thenReturn(null, null);
        when(redissonClient.getLock(LOCK_KEY)).thenReturn(lock);
        when(lock.tryLock(anyLong(), anyLong(), any())).thenReturn(true);
        when(productMapper.selectById(PRODUCT_ID)).thenReturn(buildProduct());
        when(skuMapper.selectList(any())).thenReturn(List.of(buildSku()));

        ProductDetailVO vo = productService.getDetail(PRODUCT_ID);

        assertThat(vo.getSkus()).hasSize(1);

        // 验证写入缓存，且 TTL 在 [BASE_TTL, BASE_TTL + RANDOM_TTL) 区间（防雪崩）
        ArgumentCaptor<String> jsonCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Duration> ttlCaptor = ArgumentCaptor.forClass(Duration.class);
        verify(valueOperations).set(eq(CACHE_KEY), jsonCaptor.capture(), ttlCaptor.capture());
        assertThat(ttlCaptor.getValue())
                .isGreaterThanOrEqualTo(ProductServiceImpl.BASE_TTL)
                .isLessThan(ProductServiceImpl.BASE_TTL.plusSeconds(ProductServiceImpl.RANDOM_TTL_SECONDS));
        // 缓存内容可反序列化回商品详情
        ProductDetailVO cached = objectMapper.readValue(jsonCaptor.getValue(), ProductDetailVO.class);
        assertThat(cached.getName()).isEqualTo("测试手机");
        // 释放锁
        verify(lock).unlock();
    }

    @Test
    void getDetailShouldCacheNullValueWhenProductMissing() throws Exception {
        // 双重检查均未命中，查库无商品 → 写入空值缓存（防穿透）并抛业务异常
        when(valueOperations.get(CACHE_KEY)).thenReturn(null, null);
        when(redissonClient.getLock(LOCK_KEY)).thenReturn(lock);
        when(lock.tryLock(anyLong(), anyLong(), any())).thenReturn(true);
        when(productMapper.selectById(PRODUCT_ID)).thenReturn(null);

        assertThatThrownBy(() -> productService.getDetail(PRODUCT_ID))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("不存在");

        verify(valueOperations).set(eq(CACHE_KEY), eq(ProductServiceImpl.NULL_VALUE),
                eq(ProductServiceImpl.NULL_TTL));
        verify(lock).unlock();
    }

    @Test
    void getDetailShouldCacheNullValueWhenProductOffShelf() throws Exception {
        // 已下架商品按不存在处理（C 端不可见），同样写空值缓存
        when(valueOperations.get(CACHE_KEY)).thenReturn(null, null);
        when(redissonClient.getLock(LOCK_KEY)).thenReturn(lock);
        when(lock.tryLock(anyLong(), anyLong(), any())).thenReturn(true);
        PmsProduct offShelf = buildProduct();
        offShelf.setStatus(0);
        when(productMapper.selectById(PRODUCT_ID)).thenReturn(offShelf);

        assertThatThrownBy(() -> productService.getDetail(PRODUCT_ID))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("不存在");

        verify(valueOperations).set(eq(CACHE_KEY), eq(ProductServiceImpl.NULL_VALUE),
                eq(ProductServiceImpl.NULL_TTL));
        verify(skuMapper, never()).selectList(any());
    }

    @Test
    void getDetailShouldUseDoubleCheckCacheAfterAcquiringLock() throws Exception {
        // 第一次读缓存未命中；抢到锁后双重检查命中（其他线程已重建）→ 不查库、不重复写缓存
        when(valueOperations.get(CACHE_KEY)).thenReturn(null, objectMapper.writeValueAsString(buildDetail()));
        when(redissonClient.getLock(LOCK_KEY)).thenReturn(lock);
        when(lock.tryLock(anyLong(), anyLong(), any())).thenReturn(true);

        ProductDetailVO vo = productService.getDetail(PRODUCT_ID);

        assertThat(vo.getId()).isEqualTo(PRODUCT_ID);
        verify(productMapper, never()).selectById(anyLong());
        verify(valueOperations, never()).set(anyString(), anyString(), any(Duration.class));
        verify(lock).unlock();
    }

    // ---------------- 商品详情：锁竞争失败（防击穿） ----------------

    @Test
    void getDetailShouldReturnCachedValueWhenLockNotAcquired() throws Exception {
        // 未抢到锁（其他线程重建中）：等待结束后重读缓存命中
        when(valueOperations.get(CACHE_KEY)).thenReturn(null, objectMapper.writeValueAsString(buildDetail()));
        when(redissonClient.getLock(LOCK_KEY)).thenReturn(lock);
        when(lock.tryLock(anyLong(), anyLong(), any())).thenReturn(false);

        ProductDetailVO vo = productService.getDetail(PRODUCT_ID);

        assertThat(vo.getId()).isEqualTo(PRODUCT_ID);
        // 未持有锁则不应释放；不查库、不写缓存
        verify(lock, never()).unlock();
        verify(productMapper, never()).selectById(anyLong());
        verify(valueOperations, never()).set(anyString(), anyString(), any(Duration.class));
    }

    @Test
    void getDetailShouldFallbackToDbWhenLockNotAcquiredAndCacheStillEmpty() throws Exception {
        // 未抢到锁且缓存仍为空（重建失败等异常场景）：查库兜底保证可用性，不写缓存
        when(valueOperations.get(CACHE_KEY)).thenReturn(null, null);
        when(redissonClient.getLock(LOCK_KEY)).thenReturn(lock);
        when(lock.tryLock(anyLong(), anyLong(), any())).thenReturn(false);
        when(productMapper.selectById(PRODUCT_ID)).thenReturn(buildProduct());
        when(skuMapper.selectList(any())).thenReturn(List.of(buildSku()));

        ProductDetailVO vo = productService.getDetail(PRODUCT_ID);

        assertThat(vo.getSkus()).hasSize(1);
        verify(valueOperations, never()).set(anyString(), anyString(), any(Duration.class));
    }

    @Test
    void getDetailShouldThrowAndRestoreInterruptFlagWhenLockInterrupted() throws Exception {
        when(valueOperations.get(CACHE_KEY)).thenReturn(null);
        when(redissonClient.getLock(LOCK_KEY)).thenReturn(lock);
        when(lock.tryLock(anyLong(), anyLong(), any())).thenThrow(new InterruptedException());

        assertThatThrownBy(() -> productService.getDetail(PRODUCT_ID))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("失败");

        assertThat(Thread.currentThread().isInterrupted()).isTrue();
        // 清除中断标记，避免影响后续测试
        Thread.interrupted();
    }

    // ---------------- 分类树 ----------------

    @Test
    void categoryTreeShouldBuildTwoLevelTreeFromEnabledCategories() {
        PmsCategory root = buildCategory(1L, 0L, "数码", 1);
        PmsCategory child = buildCategory(11L, 1L, "手机", 2);

        // mock 数据模拟 DB 查询结果：service 的 status=1 过滤在 SQL 层，mock 只返回启用分类
        when(categoryMapper.selectList(any())).thenReturn(List.of(root, child));

        List<PmsCategory> tree = productService.categoryTree();

        assertThat(tree).hasSize(1);
        assertThat(tree.get(0).getName()).isEqualTo("数码");
        assertThat(tree.get(0).getChildren()).extracting(PmsCategory::getName).containsExactly("手机");
    }

    // ---------------- 商品分页 ----------------

    @Test
    void pageShouldReturnPageResult() {
        com.baomidou.mybatisplus.extension.plugins.pagination.Page<PmsProduct> mpPage =
                new com.baomidou.mybatisplus.extension.plugins.pagination.Page<>(1, 10, 2);
        mpPage.setRecords(List.of(buildProduct()));

        when(productMapper.selectPage(any(), any())).thenReturn(mpPage);

        PageResult<PmsProduct> result = productService.page(1, 10, null, null);

        assertThat(result.getTotal()).isEqualTo(2);
        assertThat(result.getRecords()).hasSize(1);
        assertThat(result.getCurrent()).isEqualTo(1);
        assertThat(result.getSize()).isEqualTo(10);
        // 未选分类：不查分类表、条件为精确匹配而非 IN
        verify(categoryMapper, never()).selectList(any());
        assertThat(capturePageSqlSegment()).doesNotContain("IN");
    }

    @Test
    void pageShouldExpandRootCategoryToDescendantIds() {
        // 一级分类 1 下有二级分类 11、12：按 1 查询应展开为 [1, 11, 12] 的 IN 查询
        when(categoryMapper.selectList(any()))
                .thenReturn(List.of(buildCategory(11L, 1L, "手机", 2), buildCategory(12L, 1L, "电脑", 2)))
                .thenReturn(List.of());
        when(productMapper.selectPage(any(), any())).thenReturn(new com.baomidou.mybatisplus.extension.plugins.pagination.Page<>(1, 10));

        productService.page(1, 10, 1L, null);

        verify(productMapper).selectPage(any(), wrapperCaptor.capture());
        String sqlSegment = wrapperCaptor.getValue().getSqlSegment();
        assertThat(sqlSegment).contains("IN");
    }

    @Test
    void pageShouldKeepExactMatchForLeafCategory() {
        // 叶子分类（无子分类）：仅包含自身 ID，仍走 IN（单元素）条件
        when(categoryMapper.selectList(any())).thenReturn(List.of());
        when(productMapper.selectPage(any(), any())).thenReturn(new com.baomidou.mybatisplus.extension.plugins.pagination.Page<>(1, 10));

        productService.page(1, 10, 11L, null);

        verify(productMapper).selectPage(any(), wrapperCaptor.capture());
        assertThat(wrapperCaptor.getValue().getSqlSegment()).contains("IN");
    }

    /** 捕获最近一次分页查询的 SQL 片段 */
    private String capturePageSqlSegment() {
        verify(productMapper).selectPage(any(), wrapperCaptor.capture());
        return wrapperCaptor.getValue().getSqlSegment();
    }

    // ---------------- 测试数据构造 ----------------

    private PmsProduct buildProduct() {
        PmsProduct product = new PmsProduct();
        product.setId(PRODUCT_ID);
        product.setCategoryId(11L);
        product.setName("测试手机");
        product.setSubTitle("旗舰机型");
        product.setMainPic("https://cdn.example.com/p100.jpg");
        product.setDetail("<p>详情</p>");
        product.setPrice(new BigDecimal("3999.00"));
        product.setStock(50);
        product.setSales(120);
        product.setStatus(1); // 默认上架
        return product;
    }

    private PmsSku buildSku() {
        PmsSku sku = new PmsSku();
        sku.setId(1001L);
        sku.setProductId(PRODUCT_ID);
        sku.setSkuCode("P100-BLACK-256G");
        sku.setSpecJson("{\"颜色\":\"黑色\",\"存储\":\"256GB\"}");
        sku.setPrice(new BigDecimal("3999.00"));
        sku.setStock(30);
        return sku;
    }

    private ProductDetailVO buildDetail() {
        ProductDetailVO vo = new ProductDetailVO();
        vo.setId(PRODUCT_ID);
        vo.setCategoryId(11L);
        vo.setName("测试手机");
        vo.setPrice(new BigDecimal("3999.00"));
        vo.setStock(50);
        vo.setSales(120);
        vo.setStatus(1);
        vo.setSkus(List.of(buildSku()));
        return vo;
    }

    private PmsCategory buildCategory(Long id, Long parentId, String name, int level) {
        PmsCategory category = new PmsCategory();
        category.setId(id);
        category.setParentId(parentId);
        category.setName(name);
        category.setLevel(level);
        category.setSort(0);
        category.setStatus(1);
        return category;
    }
}
