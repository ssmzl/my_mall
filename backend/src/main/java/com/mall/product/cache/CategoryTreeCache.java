package com.mall.product.cache;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mall.product.entity.PmsCategory;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;

/**
 * 分类树 Redis 读缓存组件（C 端与管理端共用）。
 *
 * <p>key 约定：
 * <ul>
 *   <li>{@link #KEY_C}：C 端分类树（仅启用分类）；</li>
 *   <li>{@link #KEY_ADMIN}：管理端分类树（含禁用分类）。</li>
 * </ul>
 * 值为分类树 JSON（ObjectMapper 序列化），正常 TTL 1 小时；
 * 查询结果为空树时写 60s 短 TTL 占位值（空树序列化为 "[]"，读取时反序列化为空列表，天然区分于未命中 null）防穿透。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CategoryTreeCache {

    /** C 端分类树缓存 key */
    public static final String KEY_C = "mall:cache:category:tree:c";
    /** 管理端分类树缓存 key */
    public static final String KEY_ADMIN = "mall:cache:category:tree:admin";
    /** 正常缓存 TTL */
    public static final Duration TTL = Duration.ofHours(1);
    /** 空树占位缓存 TTL（防穿透，短过期） */
    public static final Duration EMPTY_TTL = Duration.ofSeconds(60);

    private static final TypeReference<List<PmsCategory>> TREE_TYPE = new TypeReference<>() {
    };

    private final StringRedisTemplate stringRedisTemplate;
    private final ObjectMapper objectMapper;

    /**
     * 读缓存：命中返回树（可能为空列表，表示「已缓存的空结果」）；未命中或内容损坏返回 null。
     */
    public List<PmsCategory> get(String key) {
        String json = stringRedisTemplate.opsForValue().get(key);
        if (json == null) {
            return null;
        }
        try {
            return objectMapper.readValue(json, TREE_TYPE);
        } catch (JsonProcessingException e) {
            // 缓存内容损坏按未命中处理：删除脏数据，触发后续回源重建
            log.error("分类树缓存反序列化失败，删除缓存触发重建: key={}, error={}", key, e.getMessage());
            stringRedisTemplate.delete(key);
            return null;
        }
    }

    /**
     * 写缓存：非空树 TTL 1 小时；空树写 60s 占位防穿透。
     * 序列化失败仅记录日志并放弃本次缓存（降级为直查库），不影响主流程。
     */
    public void put(String key, List<PmsCategory> tree) {
        try {
            String json = objectMapper.writeValueAsString(tree == null ? List.of() : tree);
            Duration ttl = (tree == null || tree.isEmpty()) ? EMPTY_TTL : TTL;
            stringRedisTemplate.opsForValue().set(key, json, ttl);
        } catch (JsonProcessingException e) {
            log.error("分类树缓存序列化失败，放弃本次缓存: key={}", key, e);
        }
    }

    /**
     * 驱逐 C 端与管理端两个树缓存 key（管理端分类增删改成功后调用）。
     *
     * <p>约定：当前调用方无事务上下文，直接删除；若将来操作加上事务，
     * 必须改为事务提交后执行（TransactionSynchronizationManager#afterCommit），避免「删缓存 → 提交」窗口内并发读回写脏数据。
     */
    public void evictAll() {
        stringRedisTemplate.delete(List.of(KEY_C, KEY_ADMIN));
    }
}
