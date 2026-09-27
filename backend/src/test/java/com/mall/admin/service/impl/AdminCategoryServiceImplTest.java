package com.mall.admin.service.impl;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mall.admin.dto.AdminCategoryAddDTO;
import com.mall.admin.dto.AdminCategoryUpdateDTO;
import com.mall.common.BizException;
import com.mall.product.cache.CategoryTreeCache;
import com.mall.product.entity.PmsCategory;
import com.mall.product.mapper.CategoryMapper;
import com.mall.product.mapper.ProductMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 管理端分类维护单元测试：层级校验、删除保护、管理端树（含禁用分类）。
 */
@ExtendWith(MockitoExtension.class)
class AdminCategoryServiceImplTest {

    @Mock
    private CategoryMapper categoryMapper;

    @Mock
    private ProductMapper productMapper;

    @Mock
    private StringRedisTemplate stringRedisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    private AdminCategoryServiceImpl service;

    @BeforeEach
    void setUp() {
        // lambda 条件依赖 TableInfo 元数据，单测中手动初始化
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, PmsCategory.class);
        // 真实 CategoryTreeCache + mock Redis：tree() 缓存未命中回源，增删改后驱逐走 delete（mock 默认返回不影响断言）
        service = new AdminCategoryServiceImpl(categoryMapper, productMapper,
                new CategoryTreeCache(stringRedisTemplate, new ObjectMapper().findAndRegisterModules()));
        org.mockito.Mockito.lenient().when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
    }

    @Test
    void addShouldCreateLevelOneCategoryWhenParentIdIsZero() {
        AdminCategoryAddDTO dto = new AdminCategoryAddDTO();
        dto.setParentId(0L);
        dto.setName("数码");

        service.add(dto);

        ArgumentCaptor<PmsCategory> captor = ArgumentCaptor.forClass(PmsCategory.class);
        verify(categoryMapper).insert(captor.capture());
        PmsCategory inserted = captor.getValue();
        assertThat(inserted.getParentId()).isZero();
        assertThat(inserted.getLevel()).isEqualTo(1);
        assertThat(inserted.getStatus()).isEqualTo(1);
        assertThat(inserted.getSort()).isZero();
    }

    @Test
    void addShouldCreateLevelTwoCategoryUnderLevelOneParent() {
        when(categoryMapper.selectById(11L)).thenReturn(category(11L, 0L, 1));
        AdminCategoryAddDTO dto = new AdminCategoryAddDTO();
        dto.setParentId(11L);
        dto.setName("手机");
        dto.setSort(2);

        service.add(dto);

        ArgumentCaptor<PmsCategory> captor = ArgumentCaptor.forClass(PmsCategory.class);
        verify(categoryMapper).insert(captor.capture());
        assertThat(captor.getValue().getLevel()).isEqualTo(2);
        assertThat(captor.getValue().getSort()).isEqualTo(2);
    }

    @Test
    void addShouldRejectWhenParentNotExists() {
        when(categoryMapper.selectById(99L)).thenReturn(null);
        AdminCategoryAddDTO dto = new AdminCategoryAddDTO();
        dto.setParentId(99L);
        dto.setName("孤儿分类");

        assertThatThrownBy(() -> service.add(dto))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("父分类不存在");
        verify(categoryMapper, never()).insert(any(PmsCategory.class));
    }

    @Test
    void addShouldRejectThirdLevel() {
        when(categoryMapper.selectById(12L)).thenReturn(category(12L, 11L, 2));
        AdminCategoryAddDTO dto = new AdminCategoryAddDTO();
        dto.setParentId(12L);
        dto.setName("三级分类");

        assertThatThrownBy(() -> service.add(dto))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("两级");
    }

    @Test
    void updateShouldPatchOnlyNonNullFields() {
        when(categoryMapper.selectById(11L)).thenReturn(category(11L, 0L, 1));
        AdminCategoryUpdateDTO dto = new AdminCategoryUpdateDTO();
        dto.setName("数码改");
        dto.setStatus(0);

        service.update(11L, dto);

        ArgumentCaptor<PmsCategory> captor = ArgumentCaptor.forClass(PmsCategory.class);
        verify(categoryMapper).updateById(captor.capture());
        assertThat(captor.getValue().getName()).isEqualTo("数码改");
        assertThat(captor.getValue().getStatus()).isZero();
    }

    @Test
    void updateShouldRejectInvalidStatus() {
        when(categoryMapper.selectById(11L)).thenReturn(category(11L, 0L, 1));
        AdminCategoryUpdateDTO dto = new AdminCategoryUpdateDTO();
        dto.setStatus(2);

        assertThatThrownBy(() -> service.update(11L, dto))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("非法");
    }

    @Test
    void deleteShouldRejectWhenHasChildren() {
        when(categoryMapper.selectById(11L)).thenReturn(category(11L, 0L, 1));
        when(categoryMapper.selectCount(any())).thenReturn(2L);

        assertThatThrownBy(() -> service.delete(11L))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("子分类");
        verify(categoryMapper, never()).deleteById(11L);
    }

    @Test
    void deleteShouldRejectWhenHasProducts() {
        when(categoryMapper.selectById(12L)).thenReturn(category(12L, 11L, 2));
        when(categoryMapper.selectCount(any())).thenReturn(0L);
        when(productMapper.selectCount(any())).thenReturn(5L);

        assertThatThrownBy(() -> service.delete(12L))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("商品");
        verify(categoryMapper, never()).deleteById(12L);
    }

    @Test
    void deleteShouldSucceedWhenEmpty() {
        when(categoryMapper.selectById(13L)).thenReturn(category(13L, 11L, 2));
        when(categoryMapper.selectCount(any())).thenReturn(0L);
        when(productMapper.selectCount(any())).thenReturn(0L);

        service.delete(13L);

        verify(categoryMapper).deleteById(13L);
    }

    @Test
    void treeShouldIncludeDisabledCategories() {
        PmsCategory root = category(1L, 0L, 1);
        root.setStatus(0); // 禁用的一级分类也应出现在管理端树中
        PmsCategory child = category(2L, 1L, 2);
        when(categoryMapper.selectList(any())).thenReturn(List.of(root, child));

        List<PmsCategory> tree = service.tree();

        assertThat(tree).hasSize(1);
        assertThat(tree.get(0).getId()).isEqualTo(1L);
        assertThat(tree.get(0).getChildren()).extracting(PmsCategory::getId).containsExactly(2L);
    }

    @Test
    void operationsShouldRejectWhenCategoryNotExists() {
        when(categoryMapper.selectById(404L)).thenReturn(null);

        assertThatThrownBy(() -> service.update(404L, new AdminCategoryUpdateDTO()))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("分类不存在");
        assertThatThrownBy(() -> service.delete(404L))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("分类不存在");
    }

    private static PmsCategory category(Long id, Long parentId, int level) {
        PmsCategory c = new PmsCategory();
        c.setId(id);
        c.setParentId(parentId);
        c.setName("分类" + id);
        c.setLevel(level);
        c.setSort(0);
        c.setStatus(1);
        return c;
    }
}
