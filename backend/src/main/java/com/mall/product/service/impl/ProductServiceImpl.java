package com.mall.product.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mall.common.BizException;
import com.mall.common.PageResult;
import com.mall.product.dto.ProductDetailVO;
import com.mall.product.entity.PmsCategory;
import com.mall.product.entity.PmsProduct;
import com.mall.product.entity.PmsSku;
import com.mall.product.mapper.CategoryMapper;
import com.mall.product.mapper.ProductMapper;
import com.mall.product.mapper.SkuMapper;
import com.mall.product.service.ProductService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 商品服务实现。
 *
 * <p>商品详情缓存三防设计（key: mall:product:detail:{id}，值为 JSON）：
 * <ul>
 *   <li>防穿透：查无商品/已下架时写入空值标记，短 TTL（2 分钟），避免恶意 id 反复打库；</li>
 *   <li>防击穿：缓存未命中时用 Redisson 互斥锁，仅一个线程重建缓存（含双重检查），其余线程等待后重读缓存、仍无则查库兜底；</li>
 *   <li>防雪崩：正常缓存 TTL 为基础 1 小时 + 随机 0~30 分钟，避免大量 key 同时失效。</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProductServiceImpl implements ProductService {

    /** 商品详情缓存 key 前缀 */
    public static final String CACHE_KEY_PREFIX = "mall:product:detail:";
    /** 缓存重建互斥锁 key 前缀 */
    public static final String LOCK_KEY_PREFIX = "mall:product:detail:lock:";
    /** 空值缓存标记（防穿透） */
    public static final String NULL_VALUE = "__NULL__";
    /** 正常缓存基础 TTL */
    public static final Duration BASE_TTL = Duration.ofHours(1);
    /** 随机 TTL 上限（防雪崩，实际 TTL = BASE_TTL + [0, RANDOM_TTL)） */
    public static final long RANDOM_TTL_SECONDS = Duration.ofMinutes(30).toSeconds();
    /** 空值缓存 TTL（防穿透，短过期避免数据长时间不一致） */
    public static final Duration NULL_TTL = Duration.ofMinutes(2);
    /** 抢锁等待时间（防击穿） */
    public static final long LOCK_WAIT_SECONDS = 3;
    /** 锁自动释放时间（防止持有线程崩溃后死锁，Redisson 亦有看门狗兜底） */
    public static final long LOCK_LEASE_SECONDS = 10;

    private static final int STATUS_ON = 1;
    private static final int ROOT_PARENT_ID = 0;

    private final CategoryMapper categoryMapper;
    private final ProductMapper productMapper;
    private final SkuMapper skuMapper;
    private final StringRedisTemplate stringRedisTemplate;
    private final RedissonClient redissonClient;
    private final ObjectMapper objectMapper;

    @Override
    public List<PmsCategory> categoryTree() {
        List<PmsCategory> all = categoryMapper.selectList(new LambdaQueryWrapper<PmsCategory>()
                .eq(PmsCategory::getStatus, STATUS_ON)
                .orderByAsc(PmsCategory::getSort));
        // 表结构固定两级：按父ID分组后挂到一级分类下
        Map<Long, List<PmsCategory>> byParent = all.stream()
                .collect(Collectors.groupingBy(PmsCategory::getParentId));
        List<PmsCategory> roots = byParent.getOrDefault((long) ROOT_PARENT_ID, List.of());
        roots.forEach(root -> root.setChildren(byParent.getOrDefault(root.getId(), List.of())));
        return roots;
    }

    @Override
    public PageResult<PmsProduct> page(long pageNum, long pageSize, Long categoryId, String keyword) {
        // 选中一级分类时需覆盖其全部子孙分类下的商品：将分类展开为「自身 + 后代」ID 集合后 IN 查询
        List<Long> categoryIds = categoryId != null ? expandCategoryIds(categoryId) : null;
        LambdaQueryWrapper<PmsProduct> wrapper = new LambdaQueryWrapper<PmsProduct>()
                .eq(PmsProduct::getStatus, STATUS_ON)
                .in(categoryIds != null, PmsProduct::getCategoryId, categoryIds)
                // keyword 模糊匹配名称或副标题
                .and(StringUtils.hasText(keyword),
                        w -> w.like(PmsProduct::getName, keyword).or().like(PmsProduct::getSubTitle, keyword))
                // 列表不加载大字段 detail
                .select(PmsProduct.class, i -> !"detail".equals(i.getColumn()))
                .orderByDesc(PmsProduct::getSales)
                .orderByDesc(PmsProduct::getId);
        IPage<PmsProduct> result = productMapper.selectPage(new Page<>(pageNum, pageSize), wrapper);
        return PageResult.of(result);
    }

    /**
     * 将分类展开为「自身 + 全部后代分类」的 ID 集合（BFS 逐层向下查询）。
     *
     * <p>商品挂在叶子（二级）分类上，前端从首页点击一级分类进入列表时，
     * 若只按该分类精确匹配会查不到任何商品，故需展开为子树 ID 集合。
     */
    private List<Long> expandCategoryIds(Long categoryId) {
        List<Long> ids = new ArrayList<>();
        ids.add(categoryId);
        List<Long> parentIds = List.of(categoryId);
        while (!parentIds.isEmpty()) {
            List<PmsCategory> children = categoryMapper.selectList(new LambdaQueryWrapper<PmsCategory>()
                    .select(PmsCategory::getId)
                    .eq(PmsCategory::getStatus, STATUS_ON)
                    .in(PmsCategory::getParentId, parentIds));
            if (children.isEmpty()) {
                break;
            }
            List<Long> childIds = children.stream().map(PmsCategory::getId).toList();
            ids.addAll(childIds);
            parentIds = childIds;
        }
        return ids;
    }

    @Override
    public ProductDetailVO getDetail(Long id) {
        String cacheKey = CACHE_KEY_PREFIX + id;
        ProductDetailVO cached = readCache(cacheKey);
        if (cached != null) {
            return cached;
        }

        // 缓存未命中：互斥锁保证同一商品仅一个线程重建缓存（防击穿）
        RLock lock = redissonClient.getLock(LOCK_KEY_PREFIX + id);
        boolean locked = false;
        try {
            locked = lock.tryLock(LOCK_WAIT_SECONDS, LOCK_LEASE_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("获取商品缓存重建锁被中断: productId={}", id);
            throw new BizException("获取商品信息失败，请稍后重试");
        }
        try {
            if (locked) {
                // 双重检查：拿到锁前可能已有其他线程完成重建
                cached = readCache(cacheKey);
                if (cached != null) {
                    return cached;
                }
                return rebuildCache(id, cacheKey);
            }
            // 未抢到锁：等待期间其他线程大概率已重建完成，重读缓存
            cached = readCache(cacheKey);
            if (cached != null) {
                return cached;
            }
            // 缓存仍无（如重建失败），直接查库兜底，保证可用性
            log.info("缓存重建锁竞争超时且缓存为空，查库兜底: productId={}", id);
            return loadFromDb(id);
        } finally {
            if (locked) {
                lock.unlock();
            }
        }
    }

    /** 读缓存：命中正常值返回反序列化结果；命中空值标记抛异常；未命中返回 null */
    private ProductDetailVO readCache(String cacheKey) {
        String cached = stringRedisTemplate.opsForValue().get(cacheKey);
        if (cached == null) {
            return null;
        }
        if (NULL_VALUE.equals(cached)) {
            throw new BizException("商品不存在或已下架");
        }
        return deserialize(cached, cacheKey);
    }

    /** 查库重建缓存并写回（正常值随机 TTL 防雪崩，空值短 TTL 防穿透） */
    private ProductDetailVO rebuildCache(Long id, String cacheKey) {
        ProductDetailVO vo = loadFromDb(id);
        if (vo == null) {
            log.info("商品不存在或已下架，写入空值缓存: productId={}", id);
            stringRedisTemplate.opsForValue().set(cacheKey, NULL_VALUE, NULL_TTL);
            throw new BizException("商品不存在或已下架");
        }
        long ttlSeconds = BASE_TTL.toSeconds() + ThreadLocalRandom.current().nextLong(RANDOM_TTL_SECONDS);
        stringRedisTemplate.opsForValue().set(cacheKey, serialize(vo), Duration.ofSeconds(ttlSeconds));
        return vo;
    }

    /** 查库加载商品详情（含 SKU），不存在或已下架返回 null */
    private ProductDetailVO loadFromDb(Long id) {
        PmsProduct product = productMapper.selectById(id);
        if (product == null || !Integer.valueOf(STATUS_ON).equals(product.getStatus())) {
            return null;
        }
        ProductDetailVO vo = new ProductDetailVO();
        copyProduct(product, vo);
        vo.setSkus(skuMapper.selectList(new LambdaQueryWrapper<PmsSku>()
                .eq(PmsSku::getProductId, id)
                .orderByAsc(PmsSku::getId)));
        return vo;
    }

    private void copyProduct(PmsProduct source, ProductDetailVO target) {
        target.setId(source.getId());
        target.setCategoryId(source.getCategoryId());
        target.setName(source.getName());
        target.setSubTitle(source.getSubTitle());
        target.setMainPic(source.getMainPic());
        target.setDetail(source.getDetail());
        target.setPrice(source.getPrice());
        target.setStock(source.getStock());
        target.setSales(source.getSales());
        target.setStatus(source.getStatus());
        target.setCreateTime(source.getCreateTime());
        target.setUpdateTime(source.getUpdateTime());
    }

    private String serialize(ProductDetailVO vo) {
        try {
            return objectMapper.writeValueAsString(vo);
        } catch (JsonProcessingException e) {
            log.error("商品详情缓存序列化失败: productId={}", vo.getId(), e);
            throw new BizException("商品信息处理失败，请稍后重试");
        }
    }

    private ProductDetailVO deserialize(String json, String cacheKey) {
        try {
            return objectMapper.readValue(json, ProductDetailVO.class);
        } catch (JsonProcessingException e) {
            // 缓存内容损坏按未命中处理：删除脏数据，触发后续重建
            log.error("商品详情缓存反序列化失败，删除缓存触发重建: cacheKey={}, error={}", cacheKey, e.getMessage());
            stringRedisTemplate.delete(cacheKey);
            throw new BizException("商品信息处理失败，请稍后重试");
        }
    }
}
