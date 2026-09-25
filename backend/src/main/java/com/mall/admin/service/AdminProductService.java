package com.mall.admin.service;

import com.mall.admin.dto.AdminProductAddDTO;
import com.mall.admin.dto.AdminProductStatusDTO;
import com.mall.admin.dto.AdminProductUpdateDTO;
import com.mall.admin.dto.AdminSkuAddDTO;
import com.mall.admin.dto.AdminSkuUpdateDTO;
import com.mall.common.PageResult;
import com.mall.product.entity.PmsProduct;
import com.mall.product.entity.PmsSku;

/**
 * 管理端商品/SKU 维护服务。商品变更（新增/修改/上下架）均触发 ES 同步消息。
 */
public interface AdminProductService {

    /** 商品分页列表（含下架商品），keyword 模糊匹配名称/副标题，status 过滤可空 */
    PageResult<PmsProduct> page(long pageNum, long pageSize, String keyword, Integer status);

    /** 新增商品（必须挂在二级分类下），成功后发送 ES upsert 同步消息 */
    void add(AdminProductAddDTO dto);

    /** 修改商品（仅更新非空字段），成功后发送 ES upsert 同步消息 */
    void update(Long id, AdminProductUpdateDTO dto);

    /** 上架/下架，成功后发送 ES upsert 同步消息（下架后搜索不再命中） */
    void updateStatus(Long id, AdminProductStatusDTO dto);

    /** 新增 SKU（商品必须存在） */
    void addSku(Long productId, AdminSkuAddDTO dto);

    /** 修改 SKU（仅更新非空字段） */
    void updateSku(Long id, AdminSkuUpdateDTO dto);

    /** 删除 SKU */
    void deleteSku(Long id);
}
