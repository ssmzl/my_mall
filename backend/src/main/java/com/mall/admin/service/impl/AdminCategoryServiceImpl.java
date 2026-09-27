package com.mall.admin.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.mall.admin.dto.AdminCategoryAddDTO;
import com.mall.admin.dto.AdminCategoryUpdateDTO;
import com.mall.admin.service.AdminCategoryService;
import com.mall.common.BizException;
import com.mall.product.cache.CategoryTreeCache;
import com.mall.product.entity.PmsCategory;
import com.mall.product.entity.PmsProduct;
import com.mall.product.mapper.CategoryMapper;
import com.mall.product.mapper.ProductMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 管理端分类维护实现。
 *
 * <p>表结构固定两级：parentId=0 为一级分类，二级分类只能挂在一级分类下；
 * 删除前校验无子分类且分类下无商品，避免产生孤儿数据。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AdminCategoryServiceImpl implements AdminCategoryService {

    private static final long ROOT_PARENT_ID = 0L;
    private static final int LEVEL_ONE = 1;
    private static final int LEVEL_TWO = 2;
    private static final int STATUS_ON = 1;

    private final CategoryMapper categoryMapper;
    private final ProductMapper productMapper;
    private final CategoryTreeCache categoryTreeCache;

    @Override
    public void add(AdminCategoryAddDTO dto) {
        PmsCategory category = new PmsCategory();
        category.setParentId(dto.getParentId());
        category.setName(dto.getName().trim());
        category.setSort(dto.getSort() != null ? dto.getSort() : 0);
        category.setStatus(STATUS_ON);
        if (dto.getParentId() == ROOT_PARENT_ID) {
            category.setLevel(LEVEL_ONE);
        } else {
            PmsCategory parent = categoryMapper.selectById(dto.getParentId());
            if (parent == null) {
                throw new BizException("父分类不存在");
            }
            if (parent.getLevel() != LEVEL_ONE) {
                throw new BizException("分类层级固定为两级，二级分类下不能再添加子分类");
            }
            category.setLevel(LEVEL_TWO);
        }
        categoryMapper.insert(category);
        evictTreeCacheAfterChange();
        log.info("管理端新增分类成功: id={}, parentId={}, name={}", category.getId(), dto.getParentId(), dto.getName());
    }

    @Override
    public void update(Long id, AdminCategoryUpdateDTO dto) {
        PmsCategory category = requireCategory(id);
        if (StringUtils.hasText(dto.getName())) {
            category.setName(dto.getName().trim());
        }
        if (dto.getSort() != null) {
            category.setSort(dto.getSort());
        }
        if (dto.getStatus() != null) {
            requireValidStatus(dto.getStatus());
            category.setStatus(dto.getStatus());
        }
        categoryMapper.updateById(category);
        evictTreeCacheAfterChange();
        log.info("管理端修改分类成功: id={}, name={}, sort={}, status={}",
                id, category.getName(), category.getSort(), category.getStatus());
    }

    @Override
    public void delete(Long id) {
        requireCategory(id);
        Long childCount = categoryMapper.selectCount(new LambdaQueryWrapper<PmsCategory>()
                .eq(PmsCategory::getParentId, id));
        if (childCount > 0) {
            throw new BizException("该分类下存在子分类，无法删除");
        }
        Long productCount = productMapper.selectCount(new LambdaQueryWrapper<PmsProduct>()
                .eq(PmsProduct::getCategoryId, id));
        if (productCount > 0) {
            throw new BizException("该分类下存在商品，无法删除");
        }
        categoryMapper.deleteById(id);
        evictTreeCacheAfterChange();
        log.info("管理端删除分类成功: id={}", id);
    }

    @Override
    public List<PmsCategory> tree() {
        // 读缓存（key: mall:cache:category:tree:admin，增删改成功后驱逐）
        List<PmsCategory> cached = categoryTreeCache.get(CategoryTreeCache.KEY_ADMIN);
        if (cached != null) {
            return cached;
        }
        // 管理端树包含禁用分类（与前台 categoryTree 的差异仅在不过滤 status）
        List<PmsCategory> all = categoryMapper.selectList(new LambdaQueryWrapper<PmsCategory>()
                .orderByAsc(PmsCategory::getSort));
        Map<Long, List<PmsCategory>> byParent = all.stream()
                .collect(Collectors.groupingBy(PmsCategory::getParentId));
        List<PmsCategory> roots = byParent.getOrDefault(ROOT_PARENT_ID, List.of());
        roots.forEach(root -> root.setChildren(byParent.getOrDefault(root.getId(), List.of())));
        categoryTreeCache.put(CategoryTreeCache.KEY_ADMIN, roots);
        return roots;
    }

    /**
     * 分类增删改成功后驱逐 C 端与管理端两个树缓存 key。
     *
     * <p>约定：当前各操作无事务上下文，直接删除；若将来加上事务，
     * 必须改为事务提交后执行（TransactionSynchronizationManager#afterCommit），避免「删缓存 → 提交」窗口内并发读回写脏数据。
     */
    private void evictTreeCacheAfterChange() {
        categoryTreeCache.evictAll();
    }

    private PmsCategory requireCategory(Long id) {
        PmsCategory category = categoryMapper.selectById(id);
        if (category == null) {
            throw new BizException("分类不存在");
        }
        return category;
    }

    private void requireValidStatus(Integer status) {
        if (status != 0 && status != 1) {
            throw new BizException("非法的分类状态");
        }
    }
}
