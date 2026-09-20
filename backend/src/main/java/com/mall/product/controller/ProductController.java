package com.mall.product.controller;

import com.mall.common.PageResult;
import com.mall.common.Result;
import com.mall.product.dto.ProductDetailVO;
import com.mall.product.entity.PmsCategory;
import com.mall.product.entity.PmsProduct;
import com.mall.product.service.ProductService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 商品接口（GET 均在登录拦截器白名单内，游客可访问）。
 */
@Tag(name = "商品", description = "分类树、商品分页列表、商品详情")
@RestController
@RequestMapping("/product")
@RequiredArgsConstructor
public class ProductController {

    private final ProductService productService;

    @Operation(summary = "分类树", description = "仅返回启用分类，两级结构")
    @GetMapping("/category/tree")
    public Result<List<PmsCategory>> categoryTree() {
        return Result.ok(productService.categoryTree());
    }

    @Operation(summary = "商品分页列表", description = "仅上架商品，支持分类过滤与关键词模糊搜索（名称/副标题），按销量降序")
    @GetMapping("/page")
    public Result<PageResult<PmsProduct>> page(
            @RequestParam(defaultValue = "1") long pageNum,
            @RequestParam(defaultValue = "10") long pageSize,
            @RequestParam(required = false) Long categoryId,
            @RequestParam(required = false) String keyword) {
        return Result.ok(productService.page(pageNum, pageSize, categoryId, keyword));
    }

    @Operation(summary = "商品详情", description = "含 SKU 列表；带缓存三防（空值缓存/互斥锁/随机TTL）")
    @GetMapping("/{id}")
    public Result<ProductDetailVO> detail(@PathVariable Long id) {
        return Result.ok(productService.getDetail(id));
    }
}
