package com.mall.admin.controller;

import com.mall.admin.dto.AdminCategoryAddDTO;
import com.mall.admin.dto.AdminCategoryUpdateDTO;
import com.mall.admin.service.AdminCategoryService;
import com.mall.common.Result;
import com.mall.product.entity.PmsCategory;
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
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 管理端分类维护接口（/admin/** 由登录拦截器要求管理员角色）。
 */
@Tag(name = "管理端-分类", description = "分类增删改与管理端分类树（含禁用分类）")
@RestController
@RequestMapping("/admin/category")
@RequiredArgsConstructor
public class AdminCategoryController {

    private final AdminCategoryService adminCategoryService;

    @Operation(summary = "管理端分类树", description = "含禁用分类，两级结构")
    @GetMapping("/tree")
    public Result<List<PmsCategory>> tree() {
        return Result.ok(adminCategoryService.tree());
    }

    @Operation(summary = "新增分类", description = "parentId=0 为一级分类，否则挂到指定一级分类下")
    @PostMapping
    public Result<Void> add(@Valid @RequestBody AdminCategoryAddDTO dto) {
        adminCategoryService.add(dto);
        return Result.ok(null);
    }

    @Operation(summary = "修改分类", description = "仅更新非空字段（名称/排序/状态）")
    @PutMapping("/{id}")
    public Result<Void> update(@PathVariable Long id, @RequestBody AdminCategoryUpdateDTO dto) {
        adminCategoryService.update(id, dto);
        return Result.ok(null);
    }

    @Operation(summary = "删除分类", description = "存在子分类或分类下有商品时拒绝删除")
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        adminCategoryService.delete(id);
        return Result.ok(null);
    }
}
