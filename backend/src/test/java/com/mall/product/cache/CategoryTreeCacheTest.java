package com.mall.product.cache;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mall.product.entity.PmsCategory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 分类树缓存组件单元测试：读写 TTL 策略（正常 1h / 空树 60s 防穿透）、
 * 损坏内容降级重建、驱逐双 key。
 */
@ExtendWith(MockitoExtension.class)
class CategoryTreeCacheTest {

    @Mock
    private StringRedisTemplate stringRedisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    private CategoryTreeCache cache;

    @BeforeEach
    void setUp() {
        cache = new CategoryTreeCache(stringRedisTemplate, objectMapper);
        lenient().when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
    }

    @Test
    void getShouldReturnNullWhenKeyMissing() {
        when(valueOperations.get(CategoryTreeCache.KEY_C)).thenReturn(null);

        assertThat(cache.get(CategoryTreeCache.KEY_C)).isNull();
    }

    @Test
    void putShouldCacheTreeWithOneHourTtl() throws Exception {
        PmsCategory root = category(1L, 0L);
        PmsCategory child = category(11L, 1L);
        root.setChildren(List.of(child));

        cache.put(CategoryTreeCache.KEY_C, List.of(root));

        org.mockito.ArgumentCaptor<String> jsonCaptor = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(valueOperations).set(org.mockito.ArgumentMatchers.eq(CategoryTreeCache.KEY_C),
                jsonCaptor.capture(), org.mockito.ArgumentMatchers.eq(Duration.ofHours(1)));
        // 写入的 JSON 读回应还原出完整两级树
        List<PmsCategory> roundTrip = objectMapper.readValue(jsonCaptor.getValue(),
                new com.fasterxml.jackson.core.type.TypeReference<List<PmsCategory>>() {
                });
        assertThat(roundTrip).hasSize(1);
        assertThat(roundTrip.get(0).getChildren()).extracting(PmsCategory::getId).containsExactly(11L);
    }

    @Test
    void putShouldCacheEmptyTreeAsPlaceholderWithShortTtl() {
        cache.put(CategoryTreeCache.KEY_ADMIN, List.of());

        verify(valueOperations).set(org.mockito.ArgumentMatchers.eq(CategoryTreeCache.KEY_ADMIN),
                org.mockito.ArgumentMatchers.eq("[]"), org.mockito.ArgumentMatchers.eq(Duration.ofSeconds(60)));
    }

    @Test
    void getShouldReturnCachedEmptyTree() {
        // 空树占位值 "[]" 命中时应返回空列表（而非 null 未命中）
        when(valueOperations.get(CategoryTreeCache.KEY_ADMIN)).thenReturn("[]");

        assertThat(cache.get(CategoryTreeCache.KEY_ADMIN)).isEmpty();
    }

    @Test
    void getShouldDeleteCorruptedCacheAndReturnNull() {
        when(valueOperations.get(CategoryTreeCache.KEY_C)).thenReturn("{not-valid-json");

        assertThat(cache.get(CategoryTreeCache.KEY_C)).isNull();
        verify(stringRedisTemplate).delete(CategoryTreeCache.KEY_C);
    }

    @Test
    void evictAllShouldDeleteBothKeys() {
        cache.evictAll();

        verify(stringRedisTemplate).delete(List.of(CategoryTreeCache.KEY_C, CategoryTreeCache.KEY_ADMIN));
    }

    @Test
    void putShouldNotThrowWhenSerializeFails() throws Exception {
        // 用独立实例 + mock ObjectMapper 模拟序列化失败，验证降级（不写缓存、不抛异常）
        ObjectMapper failingMapper = org.mockito.Mockito.mock(ObjectMapper.class);
        CategoryTreeCache failingCache = new CategoryTreeCache(stringRedisTemplate, failingMapper);
        when(failingMapper.writeValueAsString(any()))
                .thenThrow(new com.fasterxml.jackson.databind.JsonMappingException(null, "boom"));

        failingCache.put(CategoryTreeCache.KEY_C, List.of(category(1L, 0L)));

        verify(valueOperations, never()).set(anyString(), anyString(), any(Duration.class));
    }

    private static PmsCategory category(Long id, Long parentId) {
        PmsCategory c = new PmsCategory();
        c.setId(id);
        c.setParentId(parentId);
        c.setName("分类" + id);
        c.setLevel(parentId == 0 ? 1 : 2);
        c.setSort(0);
        c.setStatus(1);
        return c;
    }
}
