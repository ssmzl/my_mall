package com.mall.admin.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.mall.admin.dto.AdminProductAddDTO;
import com.mall.admin.dto.AdminProductStatusDTO;
import com.mall.admin.dto.AdminProductUpdateDTO;
import com.mall.admin.dto.AdminSkuAddDTO;
import com.mall.admin.dto.AdminSkuUpdateDTO;
import com.mall.admin.service.AdminProductService;
import com.mall.common.BizException;
import com.mall.common.PageResult;
import com.mall.product.entity.PmsCategory;
import com.mall.product.entity.PmsProduct;
import com.mall.product.entity.PmsSku;
import com.mall.product.mapper.CategoryMapper;
import com.mall.product.mapper.ProductMapper;
import com.mall.product.mapper.SkuMapper;
import com.mall.search.mq.ProductSyncProducer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * 管理端商品/SKU 维护实现。
 *
 * <p>ES 同步策略：商品新增/修改/上下架后发送 upsert 消息（消费端以 DB 为准回查写 ES），
 * 在事务内发送，发送失败抛异常回滚本次变更，保证 DB 与 ES 最终一致；
 * SKU 字段不在 ES 文档中，SKU 增删改无需触发同步。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AdminProductServiceImpl implements AdminProductService {

    private static final int STATUS_ON = 1;
    private static final int LEVEL_TWO = 2;

    private final ProductMapper productMapper;
    private final SkuMapper skuMapper;
    private final CategoryMapper categoryMapper;
    private final ProductSyncProducer productSyncProducer;

    @Override
    public PageResult<PmsProduct> page(long pageNum, long pageSize, String keyword, Integer status) {
        LambdaQueryWrapper<PmsProduct> wrapper = new LambdaQueryWrapper<PmsProduct>()
                .eq(status != null, PmsProduct::getStatus, status)
                .and(StringUtils.hasText(keyword),
                        w -> w.like(PmsProduct::getName, keyword).or().like(PmsProduct::getSubTitle, keyword))
                // 列表不加载大字段 detail
                .select(PmsProduct.class, i -> !"detail".equals(i.getColumn()))
                .orderByDesc(PmsProduct::getId);
        IPage<PmsProduct> result = productMapper.selectPage(new Page<>(pageNum, pageSize), wrapper);
        return PageResult.of(result);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void add(AdminProductAddDTO dto) {
        requireSecondLevelCategory(dto.getCategoryId());
        PmsProduct product = new PmsProduct();
        product.setCategoryId(dto.getCategoryId());
        product.setName(dto.getName().trim());
        product.setSubTitle(dto.getSubTitle());
        product.setMainPic(dto.getMainPic());
        product.setDetail(dto.getDetail());
        product.setPrice(dto.getPrice());
        product.setStock(dto.getStock());
        product.setSales(0);
        product.setStatus(STATUS_ON);
        productMapper.insert(product);
        // 同步 ES（事务内发送，失败抛异常整体回滚）
        productSyncProducer.sendUpsert(product.getId());
        log.info("管理端新增商品成功: id={}, name={}", product.getId(), product.getName());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void update(Long id, AdminProductUpdateDTO dto) {
        PmsProduct product = requireProduct(id);
        if (dto.getCategoryId() != null) {
            requireSecondLevelCategory(dto.getCategoryId());
            product.setCategoryId(dto.getCategoryId());
        }
        if (StringUtils.hasText(dto.getName())) {
            product.setName(dto.getName().trim());
        }
        if (dto.getSubTitle() != null) {
            product.setSubTitle(dto.getSubTitle());
        }
        if (dto.getMainPic() != null) {
            product.setMainPic(dto.getMainPic());
        }
        if (dto.getDetail() != null) {
            product.setDetail(dto.getDetail());
        }
        if (dto.getPrice() != null) {
            product.setPrice(dto.getPrice());
        }
        if (dto.getStock() != null) {
            product.setStock(dto.getStock());
        }
        productMapper.updateById(product);
        productSyncProducer.sendUpsert(id);
        log.info("管理端修改商品成功: id={}", id);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateStatus(Long id, AdminProductStatusDTO dto) {
        Integer status = dto.getStatus();
        if (status != 0 && status != 1) {
            throw new BizException("非法的商品状态");
        }
        requireProduct(id);
        PmsProduct product = new PmsProduct();
        product.setId(id);
        product.setStatus(status);
        productMapper.updateById(product);
        productSyncProducer.sendUpsert(id);
        log.info("管理端商品上下架成功: id={}, status={}", id, status);
    }

    @Override
    public void addSku(Long productId, AdminSkuAddDTO dto) {
        requireProduct(productId);
        PmsSku sku = new PmsSku();
        sku.setProductId(productId);
        sku.setSkuCode(dto.getSkuCode().trim());
        sku.setSpecJson(dto.getSpecJson());
        sku.setPrice(dto.getPrice());
        sku.setStock(dto.getStock());
        skuMapper.insert(sku);
        log.info("管理端新增SKU成功: id={}, productId={}, skuCode={}", sku.getId(), productId, sku.getSkuCode());
    }

    @Override
    public void updateSku(Long id, AdminSkuUpdateDTO dto) {
        PmsSku sku = requireSku(id);
        if (StringUtils.hasText(dto.getSkuCode())) {
            sku.setSkuCode(dto.getSkuCode().trim());
        }
        if (dto.getSpecJson() != null) {
            sku.setSpecJson(dto.getSpecJson());
        }
        if (dto.getPrice() != null) {
            sku.setPrice(dto.getPrice());
        }
        if (dto.getStock() != null) {
            sku.setStock(dto.getStock());
        }
        skuMapper.updateById(sku);
        log.info("管理端修改SKU成功: id={}", id);
    }

    @Override
    public void deleteSku(Long id) {
        requireSku(id);
        skuMapper.deleteById(id);
        log.info("管理端删除SKU成功: id={}", id);
    }

    // ---------------- 内部方法 ----------------

    /** 商品必须挂在二级分类下（表结构与种子数据约定） */
    private void requireSecondLevelCategory(Long categoryId) {
        PmsCategory category = categoryMapper.selectById(categoryId);
        if (category == null) {
            throw new BizException("分类不存在");
        }
        if (category.getLevel() != LEVEL_TWO) {
            throw new BizException("商品必须挂在二级分类下");
        }
    }

    private PmsProduct requireProduct(Long id) {
        PmsProduct product = productMapper.selectById(id);
        if (product == null) {
            throw new BizException("商品不存在");
        }
        return product;
    }

    private PmsSku requireSku(Long id) {
        PmsSku sku = skuMapper.selectById(id);
        if (sku == null) {
            throw new BizException("SKU不存在");
        }
        return sku;
    }
}
