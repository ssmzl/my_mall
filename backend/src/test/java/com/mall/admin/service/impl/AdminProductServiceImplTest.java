package com.mall.admin.service.impl;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.mall.admin.dto.AdminProductAddDTO;
import com.mall.admin.dto.AdminProductStatusDTO;
import com.mall.admin.dto.AdminProductUpdateDTO;
import com.mall.admin.dto.AdminSkuAddDTO;
import com.mall.admin.dto.AdminSkuUpdateDTO;
import com.mall.common.BizException;
import com.mall.product.entity.PmsCategory;
import com.mall.product.entity.PmsProduct;
import com.mall.product.entity.PmsSku;
import com.mall.product.mapper.CategoryMapper;
import com.mall.product.mapper.ProductMapper;
import com.mall.product.mapper.SkuMapper;
import com.mall.search.mq.ProductSyncProducer;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 管理端商品/SKU 维护单元测试：二级分类校验、ES upsert 同步触发、SKU 维护。
 */
@ExtendWith(MockitoExtension.class)
class AdminProductServiceImplTest {

    private static final Long PRODUCT_ID = 1L;
    private static final Long LEVEL1_ID = 11L;
    private static final Long LEVEL2_ID = 12L;

    @Mock
    private ProductMapper productMapper;

    @Mock
    private SkuMapper skuMapper;

    @Mock
    private CategoryMapper categoryMapper;

    @Mock
    private ProductSyncProducer productSyncProducer;

    private AdminProductServiceImpl service;

    @BeforeEach
    void setUp() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, PmsProduct.class);
        TableInfoHelper.initTableInfo(assistant, PmsSku.class);
        service = new AdminProductServiceImpl(productMapper, skuMapper, categoryMapper, productSyncProducer);
        // 模拟自增主键回填，便于验证同步消息携带商品ID（非所有用例都会 insert，用 lenient）
        lenient().doAnswer(inv -> {
            PmsProduct p = inv.getArgument(0);
            p.setId(PRODUCT_ID);
            return 1;
        }).when(productMapper).insert(any(PmsProduct.class));
    }

    @Test
    void addShouldInsertAndSendUpsert() {
        when(categoryMapper.selectById(LEVEL2_ID)).thenReturn(category(LEVEL2_ID, LEVEL1_ID, 2));

        service.add(addDTO());

        ArgumentCaptor<PmsProduct> captor = ArgumentCaptor.forClass(PmsProduct.class);
        verify(productMapper).insert(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(1);
        assertThat(captor.getValue().getSales()).isZero();
        verify(productSyncProducer).sendUpsert(PRODUCT_ID);
    }

    @Test
    void addShouldRejectWhenCategoryNotSecondLevel() {
        when(categoryMapper.selectById(LEVEL1_ID)).thenReturn(category(LEVEL1_ID, 0L, 1));
        AdminProductAddDTO dto = addDTO();
        dto.setCategoryId(LEVEL1_ID);

        assertThatThrownBy(() -> service.add(dto))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("二级分类");
        verify(productMapper, never()).insert(any(PmsProduct.class));
        verify(productSyncProducer, never()).sendUpsert(any());
    }

    @Test
    void addShouldRejectWhenCategoryNotExists() {
        when(categoryMapper.selectById(404L)).thenReturn(null);
        AdminProductAddDTO dto = addDTO();
        dto.setCategoryId(404L);

        assertThatThrownBy(() -> service.add(dto))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("分类不存在");
    }

    @Test
    void updateShouldPatchNonNullFieldsAndSendUpsert() {
        when(productMapper.selectById(PRODUCT_ID)).thenReturn(product());
        AdminProductUpdateDTO dto = new AdminProductUpdateDTO();
        dto.setName("新名称");
        dto.setPrice(new BigDecimal("199.00"));

        service.update(PRODUCT_ID, dto);

        ArgumentCaptor<PmsProduct> captor = ArgumentCaptor.forClass(PmsProduct.class);
        verify(productMapper).updateById(captor.capture());
        assertThat(captor.getValue().getName()).isEqualTo("新名称");
        assertThat(captor.getValue().getPrice()).isEqualByComparingTo("199.00");
        verify(productSyncProducer).sendUpsert(PRODUCT_ID);
    }

    @Test
    void updateShouldRejectWhenProductNotExists() {
        when(productMapper.selectById(404L)).thenReturn(null);

        assertThatThrownBy(() -> service.update(404L, new AdminProductUpdateDTO()))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("商品不存在");
        verify(productSyncProducer, never()).sendUpsert(any());
    }

    @Test
    void updateStatusShouldSendUpsert() {
        when(productMapper.selectById(PRODUCT_ID)).thenReturn(product());
        AdminProductStatusDTO dto = new AdminProductStatusDTO();
        dto.setStatus(0);

        service.updateStatus(PRODUCT_ID, dto);

        ArgumentCaptor<PmsProduct> captor = ArgumentCaptor.forClass(PmsProduct.class);
        verify(productMapper).updateById(captor.capture());
        assertThat(captor.getValue().getStatus()).isZero();
        verify(productSyncProducer).sendUpsert(PRODUCT_ID);
    }

    @Test
    void updateStatusShouldRejectInvalidValue() {
        AdminProductStatusDTO dto = new AdminProductStatusDTO();
        dto.setStatus(2);

        assertThatThrownBy(() -> service.updateStatus(PRODUCT_ID, dto))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("非法");
        verify(productSyncProducer, never()).sendUpsert(any());
    }

    @Test
    void addSkuShouldBindProductId() {
        when(productMapper.selectById(PRODUCT_ID)).thenReturn(product());
        AdminSkuAddDTO dto = new AdminSkuAddDTO();
        dto.setSkuCode("SKU-001");
        dto.setPrice(new BigDecimal("99.90"));
        dto.setStock(100);

        service.addSku(PRODUCT_ID, dto);

        ArgumentCaptor<PmsSku> captor = ArgumentCaptor.forClass(PmsSku.class);
        verify(skuMapper).insert(captor.capture());
        assertThat(captor.getValue().getProductId()).isEqualTo(PRODUCT_ID);
    }

    @Test
    void addSkuShouldRejectWhenProductNotExists() {
        when(productMapper.selectById(404L)).thenReturn(null);

        assertThatThrownBy(() -> service.addSku(404L, new AdminSkuAddDTO()))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("商品不存在");
    }

    @Test
    void updateSkuShouldPatchNonNullFields() {
        when(skuMapper.selectById(5L)).thenReturn(sku());
        AdminSkuUpdateDTO dto = new AdminSkuUpdateDTO();
        dto.setStock(66);

        service.updateSku(5L, dto);

        ArgumentCaptor<PmsSku> captor = ArgumentCaptor.forClass(PmsSku.class);
        verify(skuMapper).updateById(captor.capture());
        assertThat(captor.getValue().getStock()).isEqualTo(66);
        // 其他字段保持原值
        assertThat(captor.getValue().getSkuCode()).isEqualTo("SKU-OLD");
    }

    @Test
    void deleteSkuShouldSucceedAndRejectWhenNotExists() {
        when(skuMapper.selectById(5L)).thenReturn(sku());
        service.deleteSku(5L);
        verify(skuMapper).deleteById(5L);

        when(skuMapper.selectById(404L)).thenReturn(null);
        assertThatThrownBy(() -> service.deleteSku(404L))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("SKU不存在");
    }

    // ---------------- 构造数据 ----------------

    private static AdminProductAddDTO addDTO() {
        AdminProductAddDTO dto = new AdminProductAddDTO();
        dto.setCategoryId(LEVEL2_ID);
        dto.setName("测试商品");
        dto.setPrice(new BigDecimal("99.90"));
        dto.setStock(100);
        return dto;
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

    private static PmsProduct product() {
        PmsProduct p = new PmsProduct();
        p.setId(PRODUCT_ID);
        p.setCategoryId(LEVEL2_ID);
        p.setName("测试商品");
        p.setPrice(new BigDecimal("99.90"));
        p.setStock(100);
        p.setSales(0);
        p.setStatus(1);
        return p;
    }

    private static PmsSku sku() {
        PmsSku s = new PmsSku();
        s.setId(5L);
        s.setProductId(PRODUCT_ID);
        s.setSkuCode("SKU-OLD");
        s.setPrice(new BigDecimal("99.90"));
        s.setStock(10);
        return s;
    }
}
