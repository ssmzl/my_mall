package com.mall.admin.controller;

import com.mall.admin.dto.AdminProductAddDTO;
import com.mall.admin.dto.AdminProductStatusDTO;
import com.mall.admin.dto.AdminProductUpdateDTO;
import com.mall.admin.dto.AdminSkuAddDTO;
import com.mall.admin.dto.AdminSkuUpdateDTO;
import com.mall.admin.service.AdminProductService;
import com.mall.common.PageResult;
import com.mall.common.Result;
import com.mall.product.entity.PmsProduct;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 管理端商品/SKU 维护接口（商品变更触发 ES 同步）。
 */
@Tag(name = "管理端-商品", description = "商品分页/增改/上下架、SKU 维护；商品变更发送 ES upsert 同步消息")
@RestController
@RequestMapping("/admin/product")
@RequiredArgsConstructor
public class AdminProductController {

    private final AdminProductService adminProductService;

    @Operation(summary = "商品分页列表", description = "含下架商品，keyword 模糊匹配名称/副标题，status 可选过滤")
    @GetMapping("/page")
    public Result<PageResult<PmsProduct>> page(
            @RequestParam(defaultValue = "1") long pageNum,
            @RequestParam(defaultValue = "10") long pageSize,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) Integer status) {
        return Result.ok(adminProductService.page(pageNum, pageSize, keyword, status));
    }

    @Operation(summary = "新增商品", description = "必须挂在二级分类下，成功后触发 ES 同步")
    @PostMapping
    public Result<Void> add(@Valid @RequestBody AdminProductAddDTO dto) {
        adminProductService.add(dto);
        return Result.ok(null);
    }

    @Operation(summary = "修改商品", description = "仅更新非空字段，成功后触发 ES 同步")
    @PutMapping("/{id}")
    public Result<Void> update(@PathVariable Long id, @RequestBody AdminProductUpdateDTO dto) {
        adminProductService.update(id, dto);
        return Result.ok(null);
    }

    @Operation(summary = "上架/下架", description = "status: 0 下架 1 上架，变更触发 ES 同步（下架后搜索不再命中）")
    @PutMapping("/status/{id}")
    public Result<Void> updateStatus(@PathVariable Long id, @Valid @RequestBody AdminProductStatusDTO dto) {
        adminProductService.updateStatus(id, dto);
        return Result.ok(null);
    }

    @Operation(summary = "新增SKU", description = "商品必须存在；变更后重算 SKU 最低价为商品展示价并触发 ES 同步、清详情缓存")
    @PostMapping("/{productId}/sku")
    public Result<Void> addSku(@PathVariable Long productId, @Valid @RequestBody AdminSkuAddDTO dto) {
        adminProductService.addSku(productId, dto);
        return Result.ok(null);
    }

    @Operation(summary = "修改SKU", description = "仅更新非空字段；变更后重算 SKU 最低价为商品展示价并触发 ES 同步、清详情缓存")
    @PutMapping("/sku/{id}")
    public Result<Void> updateSku(@PathVariable Long id, @RequestBody AdminSkuUpdateDTO dto) {
        adminProductService.updateSku(id, dto);
        return Result.ok(null);
    }

    @Operation(summary = "删除SKU", description = "删除后重算 SKU 最低价为商品展示价（无 SKU 保持原价）并触发 ES 同步、清详情缓存")
    @DeleteMapping("/sku/{id}")
    public Result<Void> deleteSku(@PathVariable Long id) {
        adminProductService.deleteSku(id);
        return Result.ok(null);
    }
}
